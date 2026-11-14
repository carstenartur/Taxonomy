package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PortableRows.Query;
import com.taxonomy.exchange.backup.PrincipalScopeCapture;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.backup.WorkspaceGitHistoryReferences.Field;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import static com.taxonomy.exchange.backup.PortableRows.*;

/** Portable routing, ownership and durable editor schemas. No source storage names, credentials or live locks. */
public final class WorkspaceBackupContributor implements BackupDataContributor {
    private static final com.fasterxml.jackson.databind.ObjectReader INDEX_RESULT_READER = PortableRows.json().readerFor(IndexResult.class);
    private final DataSource database;
    private final PortableRows rows;
    private final BackupDocumentProjector projector;
    public WorkspaceBackupContributor(DataSource database, BackupDocumentProjector projector) {
        this.database = database;
        rows = new PortableRows(database); this.projector = Objects.requireNonNull(projector);
    }
    @Override public BackupComponentId componentId() { return new BackupComponentId("workspace"); }
    /** Ownership references only; audit/Git author labels never become login or ownership mappings. */
    public Set<String> principalScopes(SnapshotContext snapshot, BackupCheckpoint checkpoint) throws IOException {
        var scope = new BackupRowScope(snapshot); var queries = new ArrayList<Query>();
        if (!scope.selectedVersion()) {
            var repositories = scope.repositoryIds("repository_id"); var workspaces = scope.repositories("source_repository_id", "workspace_id");
            var sync = scope.repositories("w.source_repository_id", "w.workspace_id");
            var forks = scope.centralTransfer("f.target_repository_id", "f.source_repository_id");
            queries.add(new Query("select f.target_repository_id as repository_id,cast(null as varchar(255)) as workspace_id,"
                    + "cast(null as varchar(255)) as linked_workspace_id,0 as repository_only,f.actor_scope as principal_scope "
                    + centralFetchSources(snapshot.authorization().request().profile()) + " where " + forks.sql() + " order by f.operation_id", forks.parameters()));
            queries.add(new Query("select repository_id,cast(null as varchar(255)) as workspace_id,cast(null as varchar(255)) as linked_workspace_id,1 as repository_only,owner_id as principal_scope from system_repository where "
                    + repositories.sql() + " and (owner_type='USER' or owner_type is null) order by repository_id", repositories.parameters()));
            var memberships = activeMemberships(repositories);
            queries.add(new Query("select repository_id,cast(null as varchar(255)) as workspace_id,cast(null as varchar(255)) as linked_workspace_id,1 as repository_only,username as principal_scope from repository_membership where "
                    + memberships.sql() + " order by id", memberships.parameters()));
            queries.add(new Query("select source_repository_id as repository_id,workspace_id,workspace_id as linked_workspace_id,0 as repository_only,username as principal_scope from user_workspace where "
                    + workspaces.sql() + " order by workspace_id", workspaces.parameters()));
            var branchProgress = scope.repositories("repository_id", "workspace_id");
            queries.add(new Query("select repository_id,workspace_id,workspace_id as linked_workspace_id,0 as repository_only,"
                    + "username as principal_scope from workspace_branch_sync where " + branchProgress.sql()
                    + " order by scope_key", branchProgress.parameters()));
            queries.add(new Query("select w.source_repository_id as repository_id,w.workspace_id,s.workspace_id as linked_workspace_id,0 as repository_only,s.username as principal_scope from sync_state s join user_workspace w on w.workspace_id=s.workspace_id where "
                    + sync.sql() + " order by s.id", sync.parameters()));
        }
        var repositoryIds = snapshot.repositories().keySet().stream().map(BackupRepositoryKey::repositoryId).collect(java.util.stream.Collectors.toSet());
        return new PrincipalScopeCapture(rows).capture(queries, row -> {
            String repository = text(row, "repository_id"), workspace = text(row, "workspace_id");
            if (!Objects.equals(workspace, text(row, "linked_workspace_id")))
                throw new IOException("Principal reference belongs to a different workspace");
            if (!scope.installation() && !(row.getInt("repository_only") == 1 ? repositoryIds.contains(repository)
                    : snapshot.repositories().containsKey(new BackupRepositoryKey(repository, workspace))))
                throw new IOException("Principal reference belongs to a different repository scope");
            return new PrincipalScopeCapture.Scope(text(row, "principal_scope"));
        }, checkpoint);
    }

    private Query activeMemberships(Query repositories) throws IOException {
        if (StagedWorkspaceSchema.missingColumnBefore(database, "repository_membership", "revoked_at", "32.4")) {
            // Before V30 a revoked membership was physically removed.
            return repositories;
        }
        return new Query("revoked_at is null and (" + repositories.sql() + ")", repositories.parameters());
    }

    /** Historical actor labels are optional identity evidence, never ownership or current grants. */
    public Set<String> historyActorScopes(SnapshotContext snapshot, BackupCheckpoint checkpoint) throws IOException {
        var scope = new BackupRowScope(snapshot); var queries = new ArrayList<Query>();
        if (scope.history() && !scope.selectedVersion()) {
            var history = scope.repositories("w.repository_id", "w.workspace_id");
            for (String table : List.of("editor_operation", "editor_checkpoint"))
                queries.add(new Query("select w.repository_id,w.workspace_id,w.scope_id as parent_scope,h.scope_id as child_scope,h.actor as principal_scope from "
                        + table + " h join editor_workspace w on w.scope_id=h.scope_id where " + history.sql() + " order by h.id", history.parameters()));
        }
        var captured = new HashSet<String>();
        if (!scope.selectedVersion() && !StagedWorkspaceSchema.predatesPublications(database)) {
            var selected = scope.repositories("p.repository_id","p.workspace_id");
            String query = """
                    select p.repository_id,p.workspace_id,p.workspace_owner_scope,p.actor_scope,
                           w.workspace_id as parent_workspace,w.source_repository_id as parent_repository,w.username as parent_owner
                    from workspace_publication_operation p join user_workspace w on w.workspace_id=p.workspace_id
                        and w.source_repository_id=p.repository_id and w.username=p.workspace_owner_scope
                    where p.workspace_owner_scope<>p.actor_scope and
                    """ + selected.sql() + " order by p.operation_id";
            captured.addAll(new PrincipalScopeCapture(rows).capture(List.of(new Query(query,selected.parameters())),row -> {
                if (!scope.includesWorkspace(text(row,"repository_id"),text(row,"workspace_id"))
                        || !samePublicationOwner(row)) throw new IOException("Publication actor belongs to a different working copy");
                return new PrincipalScopeCapture.Scope(text(row,"actor_scope"));
            },checkpoint));
        }
        captured.addAll(new PrincipalScopeCapture(rows).capture(queries, row -> {
            if (!scope.includesWorkspace(text(row, "repository_id"), text(row, "workspace_id"))
                    || !Objects.equals(text(row, "parent_scope"), text(row, "child_scope")))
                throw new IOException("Historical actor belongs to a different editor scope");
            String actor = text(row, "principal_scope");
            // Old audit labels have a broader domain than registered identity scopes.
            // Preserve their raw journal evidence and existing private quarantine.
            if (actor != null && (actor.isBlank() || actor.length() > 255 || actor.chars().anyMatch(Character::isISOControl))) actor = null;
            return new PrincipalScopeCapture.Scope(actor);
        }, checkpoint));
        return Set.copyOf(captured);
    }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() {
        return Set.of("com.taxonomy.workspace.model.SystemRepository", "com.taxonomy.workspace.model.UserWorkspace",
                "com.taxonomy.workspace.model.RepositoryMembership", "com.taxonomy.workspace.model.SyncState",
                "com.taxonomy.workspace.service.WorkspaceFetchOperation", "com.taxonomy.workspace.service.CentralFetchOperation",
                "com.taxonomy.workspace.service.WorkspacePublicationOperation", "com.taxonomy.workspace.service.WorkspacePublicationDecision",
                "com.taxonomy.workspace.model.WorkspaceBranchSyncState",
                "com.taxonomy.editor.persistence.EditorWorkspace", "com.taxonomy.editor.persistence.EditorOperation",
                "com.taxonomy.editor.persistence.EditorCheckpoint", "com.taxonomy.versioning.model.ArchitectureCommitIndex",
                "com.taxonomy.versioning.model.ContextHistoryRecord",
                "com.taxonomy.versioning.service.HistoryIndexOperationRow", "com.taxonomy.versioning.service.HistoryIndexDecisionRow");
    }
    @Override public List<String> omissions(BackupProfile profile) {
        var omissions = new ArrayList<String>();
        if (!profile.includesHistory()) omissions.add("workspace: editor operations, publication decisions, checkpoint bodies, commit index and context history are excluded");
        if (profile == BackupProfile.SELECTED_VERSION) omissions.add("workspace: current unversioned working copies and routing metadata are excluded; selected Git state is authoritative");
        if (profile != BackupProfile.INSTALLATION_FULL) omissions.add("workspace: cross-context navigation history is outside the selected scope");
        omissions.add("workspace: physical storage names, runtime row versions, provisioning errors, fork acknowledgement authority and remote credentials are target-owned");
        return List.copyOf(omissions);
    }
    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        var scope = new BackupRowScope(snapshot); var profile = snapshot.authorization().request().profile();
        var repositories = scope.repositoryIds("repository_id");
        write(sink, "repositories", profile, query(scope, false, "select repository_id,slug,display_name,description,visibility,lifecycle_state,owner_type,owner_id,topology_mode,default_branch,upstream_repository_id,upstream_branch,fork_point_commit,last_fetch_at,last_push_at,last_fetch_commit,primary_repo,created_by,created_at,updated_at from system_repository", repositories, "repository_id"),
                scoped(scope,"repository_id",null,r -> new RepositoryRecord(text(r,"repository_id"),text(r,"slug"),text(r,"display_name"),text(r,"description"),
                        enumeration(r,"visibility",RepositoryVisibility.class),enumeration(r,"lifecycle_state",RepositoryLifecycleState.class),
                        enumeration(r,"owner_type",RepositoryOwnerType.class),text(r,"owner_id"),enumeration(r,"topology_mode",RepositoryTopologyMode.class),
                        text(r,"default_branch"),text(r,"upstream_repository_id"),text(r,"upstream_branch"),text(r,"fork_point_commit"),
                        instant(r,"last_fetch_at"),instant(r,"last_push_at"),text(r,"last_fetch_commit"),r.getBoolean("primary_repo"),
                        text(r,"created_by"),instant(r,"created_at"),instant(r,"updated_at"))));
        var workspaces = scope.repositories("source_repository_id", "workspace_id");
        write(sink,"workspaces",profile,query(scope,false,"select workspace_id,username,display_name,current_branch,base_branch,shared,created_at,last_accessed_at,provisioning_status,topology_mode,source_repository_id,source_branch,relationship_type,base_commit,current_commit,last_fetched_commit,last_integrated_commit,sync_target_branch,provisioned_at,description,archived,is_default,collaborative from user_workspace",workspaces,"workspace_id"),
                scoped(scope,"source_repository_id","workspace_id",r -> new WorkspaceRecord(text(r,"workspace_id"),text(r,"username"),text(r,"display_name"),text(r,"current_branch"),text(r,"base_branch"),r.getBoolean("shared"),
                        instant(r,"created_at"),instant(r,"last_accessed_at"),enumeration(r,"provisioning_status",WorkspaceProvisioningStatus.class),
                        enumeration(r,"topology_mode",RepositoryTopologyMode.class),text(r,"source_repository_id"),text(r,"source_branch"),
                        enumeration(r,"relationship_type",WorkspaceRelationshipType.class),text(r,"base_commit"),text(r,"current_commit"),text(r,"last_fetched_commit"),
                        text(r,"last_integrated_commit"),text(r,"sync_target_branch"),instant(r,"provisioned_at"),text(r,"description"),r.getBoolean("archived"),r.getBoolean("is_default"),r.getBoolean("collaborative"))));
        var activeMemberships = scope.selectedVersion() ? repositories : activeMemberships(repositories);
        write(sink,"memberships",profile,query(scope,false,"select id,repository_id,username,repository_role,created_at,created_by,updated_at from repository_membership",activeMemberships,"id"),
                scoped(scope,"repository_id",null,r -> new MembershipRecord(reference(r,"workspace.membership","id"),text(r,"repository_id"),text(r,"username"),enumeration(r,"repository_role",RepositoryRole.class),instant(r,"created_at"),text(r,"created_by"),instant(r,"updated_at"))));
        var sync = scope.repositories("w.source_repository_id","w.workspace_id");
        write(sink,"synchronization",profile,query(scope,false,"select w.source_repository_id as captured_repository_id,w.workspace_id as captured_workspace_id,s.id,s.username,s.workspace_id,s.last_synced_commit_id,s.last_sync_timestamp,s.last_published_commit_id,s.last_publish_timestamp,s.sync_status,s.unpublished_commit_count,s.created_at,s.updated_at from sync_state s join user_workspace w on w.workspace_id=s.workspace_id",sync,"s.id"),
                scoped(scope,"captured_repository_id","captured_workspace_id",linked("captured_workspace_id","workspace_id",r -> new SyncRecord(reference(r,"workspace.sync","id"),text(r,"username"),text(r,"workspace_id"),text(r,"last_synced_commit_id"),instant(r,"last_sync_timestamp"),
                        text(r,"last_published_commit_id"),instant(r,"last_publish_timestamp"),text(r,"sync_status"),r.getInt("unpublished_commit_count"),instant(r,"created_at"),instant(r,"updated_at")))));
        var fetchScope = scope.repositories("f.repository_id", "f.workspace_id");
        var branchProgress = scope.repositories("repository_id", "workspace_id");
        write(sink, "branch-synchronization", profile, query(scope, false, """
                select scope_key,username,repository_id,workspace_id,sync_branch,last_synced_commit_id,last_sync_timestamp,
                       last_published_commit_id,last_publish_timestamp,sync_status,unpublished_commit_count,created_at,updated_at
                from workspace_branch_sync""", branchProgress, "scope_key"), scoped(scope,"repository_id","workspace_id",r -> {
            var identity = new com.taxonomy.workspace.model.WorkspaceSyncScope(text(r,"username"), text(r,"repository_id"),
                    text(r,"workspace_id"), text(r,"sync_branch"));
            if (!identity.key().equals(text(r,"scope_key"))) throw new IOException("Invalid branch synchronization scope");
            return new BranchSyncRecord(new SourceRecordId("workspace.branch-sync", identity.key()), identity.username(),
                    identity.repositoryId(), identity.workspaceId(), identity.branch(), text(r,"last_synced_commit_id"),
                    instant(r,"last_sync_timestamp"), text(r,"last_published_commit_id"), instant(r,"last_publish_timestamp"),
                    text(r,"sync_status"), r.getInt("unpublished_commit_count"), instant(r,"created_at"), instant(r,"updated_at"));
        }));
        String parentJoin = profile == BackupProfile.INSTALLATION_FULL ? "left join" : "join";
        write(sink, "fetch-journal", profile, query(scope, false, "select " + FETCH_FIELDS + """
                , f.repository_id, f.workspace_id, w.workspace_id as parent_workspace,
                  w.source_repository_id as parent_repository, w.username as parent_owner
                from workspace_fetch_operation f %s user_workspace w on w.workspace_id=f.workspace_id""".formatted(parentJoin),
                fetchScope, "f.operation_id"), scoped(scope, "repository_id", "workspace_id", r -> {
                    boolean detached = text(r,"parent_workspace") == null;
                    if (!detached && (!Objects.equals(text(r,"workspace_id"),text(r,"parent_workspace"))
                            || !Objects.equals(text(r,"repository_id"), text(r,"parent_repository"))
                            || !Objects.equals(text(r,"actor_scope"), text(r,"parent_owner")))) {
                        throw new IOException("Fetch journal ownership does not match the captured workspace");
                    }
                    return fetchRecord(r, "workspace.fetch", text(r,"repository_id"), text(r,"workspace_id"), null, detached);
                }));
        var forkScope = scope.centralTransfer("f.target_repository_id", "f.source_repository_id");
        write(sink, "central-fetch-journal", profile, query(scope, false, "select " + FETCH_FIELDS + """
                , f.target_repository_id, f.source_repository_id, t.repository_id as current_target, s.repository_id as current_source
                """ + centralFetchSources(profile),
                forkScope, "f.operation_id"), r -> {
                    String target = text(r,"target_repository_id"), source = text(r,"source_repository_id");
                    if (!scope.includesWorkspace(target, null) || !scope.includesWorkspace(source, null)) {
                        throw new IOException("Central fetch belongs to a different repository");
                    }
                    boolean detached = text(r,"current_target") == null || text(r,"current_source") == null;
                    return fetchRecord(r, "workspace.central-fetch", target, null, source, detached);
                });
        writePublications(scope, profile, sink);
        var editors = scope.repositories("repository_id","workspace_id");
        write(sink,"working-states",profile,query(scope,false,"select scope_id,repository_id,workspace_id,branch,dsl,semantic_revision,checkpoint_commit,checkpoint_revision,pending_checkpoint from editor_workspace",editors,"scope_id"),
                scoped(scope,"repository_id","workspace_id",r -> new WorkingStateRecord(reference(r,"workspace.editor","scope_id"),text(r,"repository_id"),text(r,"workspace_id"),text(r,"branch"),document(scope,unframe(text(r,"dsl"))),r.getLong("semantic_revision"),text(r,"checkpoint_commit"),r.getLong("checkpoint_revision"),text(r,"pending_checkpoint"))));
        var editorHistory = scope.repositories("w.repository_id","w.workspace_id");
        write(sink,"operations",profile,query(scope,true,"select w.repository_id as captured_repository_id,w.workspace_id as captured_workspace_id,w.scope_id as captured_editor_id,o.id,o.scope_id,o.command_id,o.actor,o.occurred_at,o.rationale,o.correlation_id,o.causation_id,o.kind,o.target_operation_id,o.previous_revision,o.semantic_revision,o.fingerprint,o.body_version,o.before_dsl,o.after_dsl,o.affected_ids from editor_operation o join editor_workspace w on w.scope_id=o.scope_id",editorHistory,"o.id"),
                scoped(scope,"captured_repository_id","captured_workspace_id",linked("captured_editor_id","scope_id",r -> new OperationRecord(reference(r,"workspace.operation","id"),reference(r,"workspace.editor","scope_id"),text(r,"command_id"),text(r,"actor"),text(r,"occurred_at"),text(r,"rationale"),text(r,"correlation_id"),text(r,"causation_id"),text(r,"kind"),text(r,"target_operation_id"),r.getLong("previous_revision"),r.getLong("semantic_revision"),text(r,"fingerprint"),r.getInt("body_version"),unframe(text(r,"before_dsl")),unframe(text(r,"after_dsl")),unframe(text(r,"affected_ids"))))));
        write(sink,"checkpoints",profile,query(scope,true,"select w.repository_id as captured_repository_id,w.workspace_id as captured_workspace_id,w.scope_id as captured_editor_id,c.id,c.scope_id,c.command_id,c.actor,c.occurred_at,c.rationale,c.fingerprint,c.from_revision,c.semantic_revision,c.expected_commit,c.dsl,c.commit_id,c.completed,c.commit_created,c.failure_code,c.origin from editor_checkpoint c join editor_workspace w on w.scope_id=c.scope_id",editorHistory,"c.id"),
                scoped(scope,"captured_repository_id","captured_workspace_id",linked("captured_editor_id","scope_id",r -> new CheckpointRecord(reference(r,"workspace.checkpoint","id"),reference(r,"workspace.editor","scope_id"),text(r,"command_id"),text(r,"actor"),text(r,"occurred_at"),text(r,"rationale"),text(r,"fingerprint"),r.getLong("from_revision"),r.getLong("semantic_revision"),text(r,"expected_commit"),unframe(text(r,"dsl")),text(r,"commit_id"),r.getBoolean("completed"),r.getBoolean("commit_created"),text(r,"failure_code"),text(r,"origin")))));
        write(sink,"commit-index",profile,query(scope,true,"select id,repository_id,workspace_id,workspace_scope_key,commit_id,author,commit_timestamp,message,changed_files,tokenized_change_text,affected_element_ids,affected_relation_ids,branch,indexed_at from architecture_commit_index",editors,"id"),
                scoped(scope,"repository_id","workspace_id",r -> new CommitIndexRecord(reference(r,"workspace.commit-index","id"),text(r,"repository_id"),text(r,"workspace_id"),text(r,"workspace_scope_key"),text(r,"commit_id"),text(r,"author"),instant(r,"commit_timestamp"),text(r,"message"),text(r,"changed_files"),text(r,"tokenized_change_text"),text(r,"affected_element_ids"),text(r,"affected_relation_ids"),text(r,"branch"),instant(r,"indexed_at"))));
        writeHistoryIndex(scope, profile, sink);
        write(sink,"context-history",profile,profile == BackupProfile.INSTALLATION_FULL ? new Query("select id,username,from_context_id,to_context_id,from_branch,to_branch,from_commit_id,to_commit_id,reason,origin_context_id,created_at from context_history_record order by id",List.of()) : null,
                r -> new ContextHistoryRecord(reference(r,"workspace.context-history","id"),text(r,"username"),text(r,"from_context_id"),text(r,"to_context_id"),text(r,"from_branch"),text(r,"to_branch"),text(r,"from_commit_id"),text(r,"to_commit_id"),text(r,"reason"),text(r,"origin_context_id"),instant(r,"created_at")));
    }
    private void writeHistoryIndex(BackupRowScope scope, BackupProfile profile, ComponentSink sink) throws IOException {
        if (scope.history() && !scope.selectedVersion() && StagedWorkspaceSchema.predatesHistoryIndex(database)) return;
        var selection = scope.repositories("p.repository_id", "p.workspace_id");
        boolean full = profile == BackupProfile.INSTALLATION_FULL;
        String target = """
                join system_repository s on s.repository_id=p.repository_id
                left join user_workspace w on w.workspace_id=p.workspace_id
                """;
        if (full) target = target.replace("join system_repository", "left join system_repository");
        else selection = new Query("(" + selection.sql() + ") and (p.workspace_id is null or w.workspace_id is not null)",
                selection.parameters());
        String parents = "s.repository_id as related_repository,w.workspace_id as related_workspace,"
                + "w.source_repository_id as workspace_repository,w.username as current_owner";
        var operation = "select p.operation_id,p.command_kind,p.cause_id,p.actor_id,p.login_kind,p.collaborative,p.workspace_owner,p.repository_scope,"
                + "p.repository_id,p.workspace_id,p.branch_name,p.captured_commit,p.projection_source_key,p.captured_generation,"
                + "p.phase,p.attempts,p.result_count,p.projection_result,p.executor_id,p.reason_code,p.created_at,p.attempted_at,"
                + "p.completed_at,p.archived_at," + parents + " from history_index_operation p " + target;
        write(sink, "index-recovery", profile, query(scope, true, operation, selection, "p.operation_id"),
                scoped(scope, "repository_id", "workspace_id", row -> {
                    boolean detached = detachedIndexTarget(row, full);
                    return new IndexRecoveryRecord(reference(row,"workspace.index-recovery","operation_id"),text(row,"command_kind"),
                            text(row,"cause_id"),text(row,"actor_id"),text(row,"login_kind"),row.getBoolean("collaborative"),
                            text(row,"repository_id"),text(row,"workspace_id"),
                            text(row,"workspace_owner"),text(row,"repository_scope"),text(row,"branch_name"),text(row,"captured_commit"),
                            text(row,"projection_source_key"),text(row,"captured_generation"),text(row,"phase"),row.getInt("attempts"),
                            row.getObject("result_count", Integer.class), indexResult(text(row,"projection_result",2048)),
                            text(row,"executor_id"),text(row,"reason_code"),
                            row.getLong("created_at"),row.getObject("attempted_at",Long.class),row.getObject("completed_at",Long.class),
                            row.getObject("archived_at",Long.class),detached);
                }));
        var decisions = "select d.decision_id,d.operation_id,d.actor_id,d.decision_code,d.occurred_at,"
                + "p.operation_id as related_operation,p.repository_id,p.workspace_id,p.workspace_owner," + parents
                + " from history_index_decision d " + (full ? "left join" : "join")
                + " history_index_operation p on p.operation_id=d.operation_id " + target;
        write(sink, "index-decisions", profile, query(scope, true, decisions, selection, "d.occurred_at,d.decision_id"),
                scoped(scope,"repository_id","workspace_id",row -> {
                    if (!Objects.equals(text(row,"operation_id"),text(row,"related_operation"))) {
                        throw new IOException("Index decision has a different operation identity");
                    }
                    detachedIndexTarget(row, full);
                    return new IndexDecisionRecord(reference(row,"workspace.index-decision","decision_id"),text(row,"operation_id"),
                            text(row,"actor_id"),text(row,"decision_code"),row.getLong("occurred_at"));
                }));
    }

    private static boolean detachedIndexTarget(ResultSet row, boolean full) throws SQLException, IOException {
        String repository = text(row,"repository_id"), workspace = text(row,"workspace_id");
        String relatedRepository = text(row,"related_repository"), relatedWorkspace = text(row,"related_workspace");
        boolean detached = relatedRepository == null || workspace != null && relatedWorkspace == null;
        if (detached && !full || relatedRepository != null && !repository.equals(relatedRepository)
                || relatedWorkspace != null && (!relatedWorkspace.equals(workspace)
                    || !repository.equals(text(row,"workspace_repository"))
                    || !Objects.equals(text(row,"workspace_owner"),text(row,"current_owner")))) {
            throw new IOException("Index recovery evidence belongs to a different repository or working copy");
        }
        return detached;
    }

    private static IndexResult indexResult(String stored) throws IOException {
        if (stored == null) return null;
        IndexResult result = INDEX_RESULT_READER.readValue(stored);
        validateIndexResult(result);
        return result;
    }

    public record IndexResult(String generation, String model, int nodes, int relations, Integer recovered,
            Integer superseded, Integer pending, String state, String currentCommit, String projectedCommit) { }

    public static void validateIndexResult(IndexResult result) throws IOException {
        if (result == null) return;
        boolean invalid = result.nodes() < 0 || result.relations() < 0
                || java.util.stream.Stream.of(result.recovered(),result.superseded(),result.pending())
                    .anyMatch(value -> value != null && value < 0)
                || java.util.stream.Stream.of(result.currentCommit(), result.projectedCommit())
                    .anyMatch(commit -> commit != null && !commit.matches("[a-f0-9]{40}"))
                || result.model() != null && !result.model().matches("[a-f0-9]{64}")
                || result.generation() != null && !result.generation().matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                || result.state() != null && !Set.of("READY","NOT_BUILT","STALE","BRANCH_MISSING","CORRUPT").contains(result.state());
        if (invalid) throw new IOException("Invalid stored index recovery result");
    }

    public record IndexRecoveryRecord(SourceRecordId sourceId, String kind, String cause, String actor, String loginKind, boolean collaborative,
            String repository, String workspace, String owner, String scope, String branch, String commit,
            String projectionSource, String generation, String phase, int attempts, Integer resultCount, IndexResult result, String executor,
            String reason, long createdAt, Long attemptedAt, Long completedAt, Long archivedAt, boolean detached) { }
    public record IndexDecisionRecord(SourceRecordId sourceId, String operationId, String actor, String decision, long occurredAt) { }

    private void writePublications(BackupRowScope scope, BackupProfile profile, ComponentSink sink) throws IOException {
        if (!scope.selectedVersion() && StagedWorkspaceSchema.predatesPublications(database)) return;
        var selected = scope.repositories("p.repository_id", "p.workspace_id");
        String parent = profile == BackupProfile.INSTALLATION_FULL ? "left join" : "join";
        String workspace = " " + parent + " user_workspace w on w.workspace_id=p.workspace_id";
        if (profile != BackupProfile.INSTALLATION_FULL)
            workspace += " and w.source_repository_id=p.repository_id and w.username=coalesce(p.workspace_owner_scope,p.actor_scope)";
        String fields = "p.repository_id,p.workspace_id,p.workspace_owner_scope,p.actor_scope,p.actor_id,p.archived_at as scope_archived_at,w.workspace_id as parent_workspace,"
                + "w.source_repository_id as parent_repository,w.username as parent_owner";
        String operation = "select " + fields + "," + PUBLICATION_COMMITS + """
                ,p.operation_id,p.causation_id,p.login_kind,p.policy_code,p.local_branch,p.source_branch,p.keep_workspace,
                 p.merged_digest,p.central_portfolio_digest,p.local_portfolio_digest,p.phase,p.operation_step,p.attempts,p.created_at,p.last_attempt_at,p.next_attempt_at,
                 p.published_at,p.completed_at,p.archived_at,p.archived_by,p.executor_id,p.decision_code,p.source_observed_at,p.pins_released_at,p.redacted_at
                 from workspace_publication_operation p""" + workspace;
        write(sink,"publication-journal",profile,query(scope,false,operation,selected,"p.operation_id"),
                scoped(scope,"repository_id","workspace_id",r -> new PublicationRecord(reference(r,"workspace.publication","operation_id"),
                        text(r,"causation_id"),text(r,"actor_id"),text(r,"actor_scope"),text(r,"login_kind"),text(r,"policy_code"),
                        text(r,"repository_id"),text(r,"workspace_id"),text(r,"local_branch"),text(r,"source_branch"),r.getBoolean("keep_workspace"),
                        publicationCommits(r),text(r,"merged_digest"),text(r,"central_portfolio_digest"),text(r,"local_portfolio_digest"),text(r,"phase"),text(r,"operation_step"),r.getInt("attempts"),
                        r.getLong("created_at"),r.getObject("last_attempt_at",Long.class),r.getObject("next_attempt_at",Long.class),
                        r.getObject("published_at",Long.class),r.getObject("completed_at",Long.class),r.getObject("archived_at",Long.class),
                        text(r,"archived_by"),text(r,"executor_id"),text(r,"decision_code"),r.getObject("source_observed_at",Long.class),
                        r.getObject("pins_released_at",Long.class),r.getObject("redacted_at",Long.class),detachedPublication(r),text(r,"workspace_owner_scope"))));
        String decisions = "select " + fields + """
                ,d.id,d.operation_id,d.original_actor,d.executor_id,d.decision_at,d.phase_code,d.decision_code,
                 p.operation_id as parent_operation from workspace_publication_decision d""" + " " + parent
                + " workspace_publication_operation p on p.operation_id=d.operation_id" + workspace;
        write(sink,"publication-decisions",profile,query(scope,true,decisions,selected,"d.id"),r -> {
            if (text(r,"parent_operation") == null || !Objects.equals(text(r,"original_actor"),text(r,"actor_id"))
                    || !scope.includesWorkspace(text(r,"repository_id"),text(r,"workspace_id")))
                throw new IOException("Publication decision does not match its original operation");
            detachedPublication(r);
            return new PublicationDecisionRecord(reference(r,"workspace.publication-decision","id"),
                    reference(r,"workspace.publication","operation_id"),text(r,"original_actor"),text(r,"executor_id"),
                    r.getLong("decision_at"),text(r,"phase_code"),text(r,"decision_code"));
        });
    }

    static final String PUBLICATION_COMMITS = "p.fork_point,p.input_source,p.input_local,p.input_tracking,"
            + "p.expected_local,p.expected_tracking,p.prepared_source,p.prepared_local,p.prepared_tracking";
    static PublicationCommits publicationCommits(ResultSet r) throws SQLException, IOException {
        return new PublicationCommits(text(r,"fork_point"),text(r,"input_source"),text(r,"input_local"),text(r,"input_tracking"),
                text(r,"expected_local"),text(r,"expected_tracking"),text(r,"prepared_source"),text(r,"prepared_local"),text(r,"prepared_tracking"));
    }
    private static boolean detachedPublication(ResultSet r) throws SQLException, IOException {
        if (text(r,"parent_workspace") == null) return true;
        if (!samePublicationOwner(r)) {
            if (r.getObject("scope_archived_at") != null) return true;
            throw new IOException("Publication operation does not match its original working copy");
        }
        return false;
    }

    static boolean samePublicationOwner(ResultSet row) throws SQLException, IOException {
        return Objects.equals(text(row,"workspace_id"),text(row,"parent_workspace"))
                && Objects.equals(text(row,"repository_id"),text(row,"parent_repository"))
                && Objects.equals(Objects.requireNonNullElse(text(row,"workspace_owner_scope"),text(row,"actor_scope")),
                        text(row,"parent_owner"));
    }

    private static String centralFetchSources(BackupProfile profile) {
        String join = profile == BackupProfile.INSTALLATION_FULL ? "left join" : "join";
        return """
                from central_fetch_operation f
                %1$s system_repository t on t.repository_id=f.target_repository_id and t.upstream_repository_id=f.source_repository_id
                    and t.upstream_branch=f.source_branch and t.fork_point_commit=f.fork_point
                %1$s system_repository s on s.repository_id=f.source_repository_id""".formatted(join);
    }

    private static final String FETCH_FIELDS = """
            f.operation_id, f.actor_id, f.actor_scope, f.local_branch, f.source_branch, f.fork_point,
            f.requested_commit, f.expected_commit, f.observed_commit, f.result_commit, f.cause_id, f.phase,
            f.attempts, f.decision_code, f.created_at, f.last_attempt_at, f.completed_at, f.login_kind,
            f.policy_code, f.archived_at, f.archived_by, f.selected_tag, f.requested_tag_object, f.result_tag_object""";

    private static FetchRecord fetchRecord(ResultSet r, String kind, String repositoryId, String workspaceId,
                                           String upstreamRepositoryId, boolean detached) throws SQLException, IOException {
        return new FetchRecord(reference(r,kind,"operation_id"), text(r,"actor_id"), text(r,"actor_scope"),
                repositoryId, workspaceId, text(r,"local_branch"), text(r,"source_branch"), text(r,"fork_point"),
                text(r,"requested_commit"), text(r,"expected_commit"), text(r,"observed_commit"), text(r,"result_commit"),
                text(r,"cause_id"), text(r,"phase"), r.getInt("attempts"), text(r,"decision_code"), r.getLong("created_at"),
                r.getObject("last_attempt_at",Long.class), r.getObject("completed_at",Long.class), text(r,"login_kind"),
                text(r,"policy_code"), r.getObject("archived_at",Long.class), text(r,"archived_by"), workspaceId == null ? null : detached,
                upstreamRepositoryId, text(r,"selected_tag"), text(r,"requested_tag_object"), text(r,"result_tag_object"),
                workspaceId == null ? detached : null);
    }
    private <T extends Record> void write(ComponentSink sink,String kind,BackupProfile profile,Query query,PortableRows.Mapper<T> mapper) throws IOException { rows.write(sink,"workspace",kind,profile,query,mapper); }
    private static <T extends Record> PortableRows.Mapper<T> scoped(BackupRowScope scope,String repositoryColumn,String workspaceColumn,PortableRows.Mapper<T> mapper) {
        return row -> {
            String repository = text(row,repositoryColumn);
            if (!(workspaceColumn == null ? scope.includesRepository(repository) : scope.includesWorkspace(repository,text(row,workspaceColumn))))
                throw new IOException("Workspace record belongs to a different captured scope");
            return mapper.read(row);
        };
    }
    private static <T extends Record> PortableRows.Mapper<T> linked(String parentColumn,String childColumn,PortableRows.Mapper<T> mapper) {
        return row -> {
            if (!Objects.equals(text(row,parentColumn),text(row,childColumn))) throw new IOException("Workspace record has an inconsistent parent link");
            return mapper.read(row);
        };
    }
    private String document(BackupRowScope scope,String dsl) { return scope.history() ? dsl : projector.currentState(dsl); }
    private static Query query(BackupRowScope scope,boolean history,String select,Query predicate,String order) {
        return scope.selectedVersion() || history && !scope.history() ? null : new Query(select + " where " + predicate.sql() + " order by " + order,predicate.parameters());
    }
    private static <E extends Enum<E>> E enumeration(ResultSet row,String column,Class<E> type) throws SQLException,IOException {
        String value=text(row,column); return value==null?null:Enum.valueOf(type,value);
    }
    public record RepositoryRecord(String repositoryId,String slug,String displayName,String description,RepositoryVisibility visibility,RepositoryLifecycleState lifecycleState,RepositoryOwnerType ownerType,String ownerScope,RepositoryTopologyMode topologyMode,String defaultBranch,String upstreamRepositoryId,String upstreamBranch,String forkPointCommit,String lastFetchAt,String lastPushAt,String lastFetchCommit,boolean primaryRepository,String createdBy,String createdAt,String updatedAt) { }
    public record WorkspaceRecord(String workspaceId,String ownerScope,String displayName,String currentBranch,String baseBranch,boolean shared,String createdAt,String lastAccessedAt,WorkspaceProvisioningStatus provisioningStatus,RepositoryTopologyMode topologyMode,String repositoryId,String sourceBranch,WorkspaceRelationshipType relationshipType,String baseCommit,String currentCommit,String lastFetchedCommit,String lastIntegratedCommit,String syncTargetBranch,String provisionedAt,String description,boolean archived,boolean defaultWorkspace,boolean collaborative) {
        public WorkspaceRecord(String workspaceId,String ownerScope,String displayName,String currentBranch,String baseBranch,boolean shared,String createdAt,String lastAccessedAt,WorkspaceProvisioningStatus provisioningStatus,RepositoryTopologyMode topologyMode,String repositoryId,String sourceBranch,WorkspaceRelationshipType relationshipType,String baseCommit,String currentCommit,String lastFetchedCommit,String lastIntegratedCommit,String syncTargetBranch,String provisionedAt,String description,boolean archived,boolean defaultWorkspace) {
            this(workspaceId,ownerScope,displayName,currentBranch,baseBranch,shared,createdAt,lastAccessedAt,provisioningStatus,topologyMode,repositoryId,sourceBranch,relationshipType,baseCommit,currentCommit,lastFetchedCommit,lastIntegratedCommit,syncTargetBranch,provisionedAt,description,archived,defaultWorkspace,false);
        }
    }
    public record MembershipRecord(SourceRecordId sourceId,String repositoryId,String principalScope,RepositoryRole role,String createdAt,String createdBy,String updatedAt) { }
    public record FetchRecord(SourceRecordId sourceId, String principalId, String principalScope, String repositoryId,
                              String workspaceId, String branch, String sourceBranch, String forkPoint, String requestedCommit,
                              String expectedCommit, String observedCommit, String resultCommit, String causationId,
                              String phase, int attempts, String decisionCode, long createdAt, Long lastAttemptAt, Long completedAt,
                              String authorizationKind, String requiredPolicy, Long archivedAt, String archivedBy, Boolean detachedWorkspace,
                              String upstreamRepositoryId, String selectedTag, String requestedTagObject, String resultTagObject, Boolean detachedCentralOrigin) { }
    public record PublicationRecord(SourceRecordId sourceId,String causationId,String originalActor,String principalScope,
            String authorizationKind,String requiredPolicy,String repositoryId,String workspaceId,String localBranch,String sourceBranch,
            boolean keepWorkspace,PublicationCommits commits,String mergedDigest,String centralPortfolioDigest,String localPortfolioDigest,
            String phase,String step,int attempts,long createdAt,
            Long lastAttemptAt,Long nextAttemptAt,Long publishedAt,Long completedAt,Long archivedAt,String archivedBy,
            String executorId,String decisionCode,Long sourceObservedAt,Long pinsReleasedAt,Long redactedAt,boolean detachedWorkspace,String workspaceOwnerScope) {
        public PublicationRecord {
            if (redactedAt!=null && (archivedAt==null || pinsReleasedAt==null || mergedDigest!=null
                    || centralPortfolioDigest!=null || localPortfolioDigest!=null || commits==null || !commits.recorded().isEmpty()))
                throw new IllegalArgumentException("Redacted publication still contains live Git details or lacks its source evidence");
        }
    }
    public record PublicationDecisionRecord(SourceRecordId sourceId,SourceRecordId operation,String originalActor,
            String executorId,long decidedAt,String step,String decisionCode) { }
    public record PublicationCommits(String forkPoint,String inputSource,String inputLocal,String inputTracking,String expectedLocal,
            String expectedTracking,String preparedSource,String preparedLocal,String preparedTracking) {
        public Map<Field,String> recorded() {
            var result = new EnumMap<Field,String>(Field.class);
            result.put(Field.PUBLICATION_FORK_POINT,forkPoint); result.put(Field.PUBLICATION_INPUT_SOURCE,inputSource);
            result.put(Field.PUBLICATION_INPUT_LOCAL,inputLocal); result.put(Field.PUBLICATION_INPUT_TRACKING,inputTracking);
            result.put(Field.PUBLICATION_EXPECTED_LOCAL,expectedLocal); result.put(Field.PUBLICATION_EXPECTED_TRACKING,expectedTracking);
            result.put(Field.PUBLICATION_PREPARED_SOURCE,preparedSource); result.put(Field.PUBLICATION_PREPARED_LOCAL,preparedLocal);
            result.put(Field.PUBLICATION_PREPARED_TRACKING,preparedTracking);
            result.values().removeIf(Objects::isNull);
            return result;
        }
    }
    public record BranchSyncRecord(SourceRecordId sourceId, String principalScope, String repositoryId, String workspaceId,
            String branch, String lastSyncedCommit, String lastSyncAt, String lastPublishedCommit, String lastPublishAt,
            String syncStatus, int unpublishedCommitCount, String createdAt, String updatedAt) { }
    public record SyncRecord(SourceRecordId sourceId,String principalScope,String workspaceId,String lastSyncedCommit,String lastSyncAt,String lastPublishedCommit,String lastPublishAt,String syncStatus,int unpublishedCommitCount,String createdAt,String updatedAt) { }
    public record WorkingStateRecord(SourceRecordId sourceId,String repositoryId,String workspaceId,String branch,String dsl,long semanticRevision,String checkpointCommit,long checkpointRevision,String interruptedCheckpoint) { }
    public record OperationRecord(SourceRecordId sourceId,SourceRecordId editor,String commandId,String actor,String occurredAt,String rationale,String correlationId,String causationId,String kind,String targetOperationId,long previousRevision,long semanticRevision,String fingerprint,int bodyVersion,String beforeDsl,String afterDsl,String affectedIds) { }
    public record CheckpointRecord(SourceRecordId sourceId,SourceRecordId editor,String commandId,String actor,String occurredAt,String rationale,String fingerprint,long fromRevision,long semanticRevision,String expectedCommit,String dsl,String commitId,boolean completed,boolean commitCreated,String failureCode,String origin) { }
    public record CommitIndexRecord(SourceRecordId sourceId,String repositoryId,String workspaceId,String workspaceScope,String commitId,String author,String committedAt,String message,String changedFiles,String changeText,String affectedElementIds,String affectedRelationIds,String branch,String indexedAt) { }
    public record ContextHistoryRecord(SourceRecordId sourceId,String principalScope,String fromContext,String toContext,String fromBranch,String toBranch,String fromCommit,String toCommit,String reason,String originContext,String createdAt) { }
}
