package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.backup.AnalysisBackupContributor.ResumePolicy;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PortableRows.Query;
import com.taxonomy.workspace.backup.BackupRowScope;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static com.taxonomy.exchange.backup.PortableRows.*;

/** Scoped, bounded capture of clustered authority. Broker dispatch and permits are never portable. */
public final class ClusterAnalysisBackupExport {
    private static final String META = "r.id as run_id,r.username,r.scope_key,r.project_id,r.requirement_id,r.context_json,r.state as run_state,r.total_roots,r.completed_roots,r.event_revision,r.created_at,r.updated_at,r.row_version";
    private final PortableRows rows;
    private final String owner;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnalysisMessageCodec codec = new AnalysisMessageCodec();

    @FunctionalInterface public interface CurrentSelection {
        boolean include(String owner, AnalysisSourceAuthority authority, String businessText, boolean terminal);
    }
    public ClusterAnalysisBackupExport(PortableRows rows, String owner) {
        this.rows = Objects.requireNonNull(rows); this.owner = Objects.requireNonNull(owner);
    }
    public void write(SnapshotContext snapshot, ComponentSink sink, CurrentSelection current) throws IOException {
        var scope = new BackupRowScope(snapshot); var profile = snapshot.authorization().request().profile();
        var selected = new TreeMap<String, Metadata>(); var latest = new HashMap<Key, Metadata>();
        Query query = scope.selectedVersion() ? null : new Query("select " + META + " from analysis_cluster_run r"
                + (scope.installation() ? "" : " where r.username=?") + " order by r.id", scope.installation() ? List.of() : List.of(owner));
        rows.visit(query, r -> metadata(r, scope), run -> {
            if (run == null) return;
            if (!scope.history()) {
                Metadata previous = latest.get(run.key());
                if (previous != null) {
                    if (run.createdAt < previous.createdAt || (run.createdAt == previous.createdAt && run.id.compareTo(previous.id) <= 0)) return;
                    selected.remove(previous.id);
                }
                latest.put(run.key(), run);
            }
            if (selected.size() >= 100_000) throw new IOException("Cluster analysis selection limit exceeded");
            selected.put(run.id, run);
        });
        var included = new TreeMap<String, Metadata>();
        rows.writeBatches(sink, "analysis", "cluster-runs", profile,
                batches("select " + META + ",r.command_json,r.view_json,r.result_json,r.relation_plan_json from analysis_cluster_run r where r.id in (", " order by r.id", selected.keySet()), r -> {
                    var run = metadata(r, scope);
                    if (run == null || !run.equals(selected.remove(run.id))) throw new IOException("Cluster analysis changed during capture");
                    String commandJson = text(r, "command_json");
                    var command = read(commandJson, AnalyzeRequirementCommand.class);
                    validateCommand(run.context, run.owner, command);
                    var requirement = run.context.requirement();
                    boolean projectLineage = requirement.projectId() != null || requirement.requirementId() != null;
                    // Project requirement snapshots are independent of the editor's ad-hoc text.
                    // Within each lineage, latest metadata already selected the current operation.
                    if (!scope.history() && !projectLineage
                            && !current.include(run.owner, run.context.authority(), command.businessText(), run.state.terminal())) return null;
                    included.put(run.id, run);
                    return new ClusterAnalysisBackupRecords.Run(reference(run.id), run.owner, run.context, commandJson,
                            text(r, "view_json"), text(r, "result_json"), text(r, "relation_plan_json"), run.state.name(),
                            ResumePolicy.REVIEW_REQUIRED, run.total, run.completed, run.revision, run.createdAt, run.updatedAt);
                });
        if (!selected.isEmpty()) throw new IOException("Cluster analysis disappeared during capture");
        var rootWork = new HashMap<String, Set<String>>(); var frozenRoots = new HashMap<String, Set<String>>();
        var completed = new HashMap<String, Integer>();
        rows.writeBatches(sink, "analysis", "cluster-work", profile,
                children("w", "analysis_cluster_work", "ordinal_number", included), r -> {
                    var run = parent(r, scope, included); String messageJson = text(r, "message_json");
                    var message = decode(messageJson);
                    String taskId = text(r, "task_id"), type = text(r, "task_type"), root = text(r, "root_code");
                    if (!(message instanceof AnalysisTaskMessage task) || !sameContext(task.envelope(), run.context)
                            || !task.taskId().value().equals(taskId) || !task.taskType().name().equals(type)
                            || !Objects.equals(task.routingRoot() == null ? null : task.routingRoot().code(), root))
                        throw new IOException("Cluster analysis task reference is inconsistent");
                    if (task.taskType() == AnalysisTaskType.SUBTAXONOMY_ANALYSIS) {
                        if (!rootWork.computeIfAbsent(run.id, ignored -> new HashSet<>()).add(root)) throw new IOException("Duplicate cluster root work");
                        if (r.getBoolean("settled")) completed.merge(run.id, 1, Integer::sum);
                    }
                    return new ClusterAnalysisBackupRecords.Work(PortableRows.reference(r, "analysis.cluster-work", "id"), reference(run.id), taskId, type,
                            root, r.getInt("ordinal_number"), messageJson, text(r, "input_json"), text(r, "result_json"), text(r, "failure_reason"),
                            text(r, "state"), r.getBoolean("settled"), r.getInt("delivery_attempts"), number(r, "started_at"), number(r, "finished_at"));
                });
        rows.writeBatches(sink, "analysis", "cluster-inputs", profile,
                children("i", "analysis_cluster_input", "root_code", included), r -> {
                    var run = parent(r, scope, included); String root = text(r, "root_code");
                    if (!TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(TaxonomyShardRoot::code).toList().contains(root)
                            || !frozenRoots.computeIfAbsent(run.id, ignored -> new HashSet<>()).add(root))
                        throw new IOException("Cluster analysis frozen root is inconsistent");
                    return new ClusterAnalysisBackupRecords.Input(PortableRows.reference(r, "analysis.cluster-input", "id"), reference(run.id), root, text(r, "input_json"));
                });
        var revisions = new HashMap<String, Long>();
        rows.writeBatches(sink, "analysis", "cluster-events", profile,
                children("e", "analysis_cluster_event", "event_revision", included), r -> {
                    var run = parent(r, scope, included); String eventJson = text(r, "event_json");
                    long revision = r.getLong("child_revision"); var message = decode(eventJson);
                    if (!(message instanceof AnalysisProgressEvent event) || !sameContext(event.envelope(), run.context)
                            || event.sequence() != revision || revision != revisions.getOrDefault(run.id, 0L) + 1 || revision > run.revision)
                        throw new IOException("Cluster analysis event reference is inconsistent");
                    revisions.put(run.id, revision);
                    return new ClusterAnalysisBackupRecords.Event(PortableRows.reference(r, "analysis.cluster-event", "id"), reference(run.id), revision, eventJson);
                });
        for (var run : included.values()) {
            var roots = rootWork.getOrDefault(run.id, Set.of());
            if (roots.size() != run.total || completed.getOrDefault(run.id, 0) != run.completed
                    || !frozenRoots.getOrDefault(run.id, Set.of()).containsAll(roots) || revisions.getOrDefault(run.id, 0L) != run.revision)
                throw new IOException("Cluster analysis dependency closure is incomplete");
        }
    }
    private List<Query> children(String alias, String table, String order, Map<String, Metadata> included) {
        return batches("select " + META + "," + alias + ".*," + alias + ".operation_id as child_run"
                + (alias.equals("e") ? ",e.event_revision as child_revision" : "")
                + " from " + table + " " + alias + " join analysis_cluster_run r on r.id=" + alias + ".operation_id where r.id in (",
                " order by r.id," + alias + "." + order, included.keySet());
    }
    private Metadata parent(ResultSet row, BackupRowScope scope, Map<String, Metadata> included) throws SQLException, IOException {
        var run = metadata(row, scope);
        if (run == null || !run.id.equals(text(row, "child_run")) || !run.equals(included.get(run.id)))
            throw new IOException("Cluster analysis child reference changed during capture");
        return run;
    }
    private Metadata metadata(ResultSet row, BackupRowScope scope) throws SQLException, IOException {
        String principal = text(row, "username"), id = text(row, "run_id");
        if (principal == null || principal.isBlank() || (!scope.installation() && !owner.equals(principal))) throw new IOException("Cluster analysis owner is inconsistent");
        String contextJson = text(row, "context_json"); var context = read(contextJson, AnalysisOperationContext.class);
        if (!context.operationId().equals(id)) throw new IOException("Cluster analysis authority is inconsistent");
        if (!scope.includesWorkspace(context.authority().repositoryId(), context.authority().workspaceId())) return null;
        var workspace = new com.taxonomy.workspace.service.WorkspaceContext(principal, context.authority().workspaceId(), context.authority().branch(), context.authority().repositoryId());
        if (!ClusterAnalysisStore.scopeKey(workspace).equals(text(row, "scope_key"))
                || !Objects.equals(context.requirement().projectId(), number(row, "project_id"))
                || !Objects.equals(context.requirement().requirementId(), number(row, "requirement_id")))
            throw new IOException("Cluster analysis indexed authority is inconsistent");
        try {
            return new Metadata(id, principal, context, contextJson, ClusterAnalysisState.valueOf(text(row, "run_state")),
                    row.getInt("total_roots"), row.getInt("completed_roots"), row.getLong("event_revision"),
                    row.getLong("created_at"), row.getLong("updated_at"), row.getLong("row_version"));
        } catch (IllegalArgumentException invalid) { throw new IOException("Cluster analysis state is invalid"); }
    }
    static void validateCommand(AnalysisOperationContext context, String owner, AnalyzeRequirementCommand command) throws IOException {
        var workspace = command.workspaceContext();
        if (workspace == null || !owner.equals(command.username()) || !owner.equals(workspace.username())
                || !context.requirement().matches(command.businessText()) || !context.authority().repositoryId().equals(workspace.repositoryId())
                || !Objects.equals(context.authority().workspaceId(), workspace.workspaceId()) || !Objects.equals(context.authority().branch(), workspace.currentBranch()))
            throw new IOException("Cluster analysis command authority is inconsistent");
    }
    static boolean sameContext(AnalysisEnvelope envelope, AnalysisOperationContext context) {
        return envelope.operationId().equals(context.operationId()) && envelope.authority().equals(context.authority())
                && envelope.requirement().equals(context.requirement()) && envelope.correlationId().equals(context.correlationId());
    }
    private <T> T read(String source, Class<T> type) throws IOException {
        try { return mapper.readValue(source, type); }
        catch (RuntimeException invalid) { throw new IOException("Invalid cluster analysis document"); }
    }
    private AnalysisMessage decode(String source) throws IOException {
        try { return codec.decode(source.getBytes(StandardCharsets.UTF_8)); }
        catch (RuntimeException invalid) { throw new IOException("Invalid cluster analysis message"); }
    }
    private static SourceRecordId reference(String id) { return new SourceRecordId("analysis.cluster-run", id); }
    private static List<Query> batches(String prefix, String suffix, Set<String> selected) {
        var ids = new ArrayList<>(selected); var queries = new ArrayList<Query>();
        for (int start = 0; start < ids.size(); start += 200) {
            var batch = ids.subList(start, Math.min(start + 200, ids.size()));
            queries.add(new Query(prefix + String.join(",", Collections.nCopies(batch.size(), "?")) + ")" + suffix, batch));
        }
        return queries;
    }
    private record Key(String owner, String repository, String workspace, String branch, Long project, Long requirement) { }
    private record Metadata(String id, String owner, AnalysisOperationContext context, String contextJson, ClusterAnalysisState state,
                            int total, int completed, long revision, long createdAt, long updatedAt, long version) {
        Key key() {
            var authority = context.authority(); var reference = context.requirement();
            return new Key(owner, authority.repositoryId(), authority.workspaceId(), authority.branch(), reference.projectId(), reference.requirementId());
        }
    }
}
