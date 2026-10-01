package com.taxonomy.catalog.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PortableRows.Query;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProjectionRecovery.RecoveryStatus;
import com.taxonomy.workspace.backup.BackupRowScope;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static com.taxonomy.exchange.backup.PortableRows.*;

/** Explicit catalogue and review schemas, selected before any payload or archive entry is read. */
public final class KnowledgeBackupContributor implements BackupDataContributor {
    private static final int MAX_IDENTITIES = 100_000;
    private final PortableRows rows;
    private final BackupAnalysisReference.Selector analyses;
    private final SelectionReader selections;

    /** The captured Git/working-document owner proves which unlinked hypotheses remain current. */
    public record Selection(Set<String> catalogueCodes, Set<SourceRecordId> currentHypotheses) {
        public Selection {
            catalogueCodes = Set.copyOf(catalogueCodes); currentHypotheses = Set.copyOf(currentHypotheses);
            if (catalogueCodes.size() > MAX_IDENTITIES || currentHypotheses.size() > MAX_IDENTITIES)
                throw new IllegalArgumentException("Knowledge selection limit exceeded");
            for (String code : catalogueCodes) if (code.isBlank() || code.length() > 1024) throw new IllegalArgumentException("Invalid catalogue code");
            for (var reference : currentHypotheses) if (!reference.kind().equals("knowledge.hypothesis"))
                throw new IllegalArgumentException("Invalid hypothesis selection kind");
        }
    }
    @FunctionalInterface public interface SelectionReader { Selection select(SnapshotContext context) throws IOException; }
    public KnowledgeBackupContributor(DataSource database, BackupAnalysisReference.Selector analyses, SelectionReader selections) {
        rows = new PortableRows(database); this.analyses = Objects.requireNonNull(analyses); this.selections = Objects.requireNonNull(selections);
    }
    @Override public BackupComponentId componentId() { return new BackupComponentId("knowledge"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() { return Set.of("com.taxonomy.catalog.model.TaxonomyNode", "com.taxonomy.catalog.model.TaxonomyRelation",
            "com.taxonomy.relations.model.RelationHypothesis", "com.taxonomy.relations.model.RelationEvidence",
            "com.taxonomy.relations.model.RelationProposal", "com.taxonomy.relations.model.RelationProjectionRecovery",
            "com.taxonomy.relations.model.RequirementCoverage"); }
    @Override public List<String> omissions(BackupProfile profile) {
        var result = new ArrayList<String>();
        result.add("knowledge: search/embedding projections, worker diagnostics and automatic recovery execution are excluded");
        result.add("knowledge: original catalogue input bytes are not inferred from shipped resources; a retained-input adapter is required separately");
        if (profile == BackupProfile.SELECTED_VERSION) result.add("knowledge: unversioned present-day catalogue/review rows are excluded; selected Git is authoritative");
        else if (!profile.includesHistory()) result.add("knowledge: earlier analysis hypotheses/evidence, completed recovery and legacy coverage text are excluded");
        if (!profile.isInstallation()) result.add("knowledge: unattributed global legacy coverage is excluded from scoped exports");
        return List.copyOf(result);
    }

    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        var scope = new BackupRowScope(snapshot); var profile = snapshot.authorization().request().profile();
        Selection selection = scope.selectedVersion() ? new Selection(Set.of(),Set.of()) : Objects.requireNonNull(selections.select(snapshot));
        Set<String> hypotheses = scope.selectedVersion() ? Set.of() : hypotheses(snapshot,scope,selection);
        Set<String> codes = new TreeSet<>(selection.catalogueCodes());
        if (!scope.selectedVersion()) {
            endpoints(scope,"taxonomy_relation",codes); endpoints(scope,"relation_proposal",codes);
            rows.visit(hypothesisQuery(scope), r -> new HypothesisEndpoint(r.getString("id"),text(r,"source_node_id"),text(r,"target_node_id")), h -> {
                if (hypotheses.contains(h.id())) { addCode(codes,h.source()); addCode(codes,h.target()); }
            });
            if (scope.installation()) rows.requireEmpty(new Query("select 1 from requirement_coverage c left join taxonomy_node n on n.code=c.node_code where n.id is null",List.of()));
        }
        Map<String,NodeIdentity> catalogue = scope.selectedVersion() ? Map.of() : catalogueClosure(scope,codes);
        rows.writeBatches(sink,"knowledge","node",profile,nodeQueries(scope,catalogue.keySet()), r -> new NodeRecord(
                reference(r,"knowledge.node","id"),text(r,"code"),text(r,"uuid"),text(r,"name_en"),text(r,"name_de"),
                text(r,"description_en"),text(r,"description_de"),text(r,"parent_code"), parentReference(catalogue,text(r,"parent_code")),
                text(r,"taxonomy_root"),r.getInt("node_level"),text(r,"dataset"),text(r,"external_id"),text(r,"source"),text(r,"reference"),
                number(r,"sort_order"),text(r,"state")));
        rows.write(sink,"knowledge","relation",profile,relationQuery(scope,"taxonomy_relation","t.description,t.provenance,t.weight,t.bidirectional"), r -> new RelationRecord(
                reference(r,"knowledge.relation","id"),text(r,"repository_id"),text(r,"workspace_id"),text(r,"owner_username"),
                reference(r,"knowledge.node","source_node_id"),reference(r,"knowledge.node","target_node_id"),text(r,"source_code"),text(r,"target_code"),
                enumeration(r,"relation_type",RelationType.class),text(r,"description"),text(r,"provenance"),number(r,"weight"),r.getBoolean("bidirectional")));
        rows.writeBatches(sink,"knowledge","hypothesis",profile,batches("select id,repository_id,workspace_id,owner_username,source_node_id,target_node_id,"
                + "relation_type,status,confidence,analysis_session_id,project_id,requirement_id,analysis_snapshot_id,applied_in_current_analysis,created_at "
                + "from relation_hypothesis where id in ",hypotheses,"id"), r -> new HypothesisRecord(
                reference(r,"knowledge.hypothesis","id"),text(r,"repository_id"),text(r,"workspace_id"),text(r,"owner_username"),
                text(r,"source_node_id"),text(r,"target_node_id"),enumeration(r,"relation_type",RelationType.class),enumeration(r,"status",HypothesisStatus.class),
                decimal(r,"confidence"),text(r,"analysis_session_id"),reference(r,"portfolio.project","project_id"),
                reference(r,"portfolio.requirement","requirement_id"),reference(r,"portfolio.analysis-snapshot","analysis_snapshot_id"),
                r.getBoolean("applied_in_current_analysis"),instant(r,"created_at")));
        rows.writeBatches(sink,"knowledge","evidence",profile,batches("select id,hypothesis_id,evidence_type,summary,full_text,confidence,model_name,model_version,"
                + "prompt_version,input_snapshot,created_at from relation_evidence where hypothesis_id in ",hypotheses,"id"), r -> new EvidenceRecord(
                reference(r,"knowledge.evidence","id"),reference(r,"knowledge.hypothesis","hypothesis_id"),text(r,"evidence_type"),text(r,"summary"),text(r,"full_text"),
                decimal(r,"confidence"),text(r,"model_name"),text(r,"model_version"),text(r,"prompt_version"),text(r,"input_snapshot"),instant(r,"created_at")));
        // A rejected decision is still the current review record, not an earlier revision.
        rows.write(sink,"knowledge","proposal",profile,relationQuery(scope,"relation_proposal","t.status,t.confidence,t.rationale,t.provenance,t.created_at,t.reviewed_at"), r -> new ProposalRecord(
                reference(r,"knowledge.proposal","id"),text(r,"repository_id"),text(r,"workspace_id"),text(r,"owner_username"),
                reference(r,"knowledge.node","source_node_id"),reference(r,"knowledge.node","target_node_id"),text(r,"source_code"),text(r,"target_code"),
                enumeration(r,"relation_type",RelationType.class),enumeration(r,"status",ProposalStatus.class),decimal(r,"confidence"),text(r,"rationale"),
                text(r,"provenance"),instant(r,"created_at"),instant(r,"reviewed_at")));
        var recoveryScope = scope.repositories("t.repository_id","t.workspace_id");
        rows.write(sink,"knowledge","projection-recovery",profile,scope.selectedVersion() ? null : new Query("select t.id,t.repository_id,t.workspace_id,t.branch,"
                + "t.previous_head_commit,t.authoritative_commit_id,t.causation_id,t.status,t.first_observed_at,t.last_observed_at,t.completed_at "
                + "from relation_projection_recovery t where " + recoveryScope.sql() + (scope.history() ? "" : " and t.status='PENDING'") + " order by t.id", recoveryScope.parameters()),
                r -> new RecoveryRecord(reference(r,"knowledge.projection-recovery","id"),text(r,"repository_id"),text(r,"workspace_id"),text(r,"branch"),
                        scope.history() ? text(r,"previous_head_commit") : null,text(r,"authoritative_commit_id"),text(r,"causation_id"),
                        enumeration(r,"status",RecoveryStatus.class),ResumePolicy.REBUILD_FROM_CAPTURE,instant(r,"first_observed_at"),instant(r,"last_observed_at"),instant(r,"completed_at")));
        rows.write(sink,"knowledge","legacy-coverage",profile,scope.selectedVersion() || !scope.installation() ? null
                : new Query("select id,requirement_id,"+(scope.history()?"requirement_text":"cast(null as varchar(2000)) as requirement_text")
                + ",node_code,score,analyzed_at from requirement_coverage order by id",List.of()), r -> new CoverageRecord(
                reference(r,"knowledge.legacy-coverage","id"),text(r,"requirement_id"),text(r,"requirement_text"),text(r,"node_code"),r.getInt("score"),instant(r,"analyzed_at")));
    }

    private Set<String> hypotheses(SnapshotContext snapshot,BackupRowScope scope,Selection selection) throws IOException {
        var analysesById = new HashMap<SourceRecordId,BackupAnalysisReference>();
        for (var reference : analyses.select(snapshot)) {
            if (analysesById.size() >= MAX_IDENTITIES || analysesById.putIfAbsent(reference.snapshot(),reference) != null)
                throw new IOException("Invalid analysis identity inventory");
        }
        var included = new TreeSet<String>(); var selected = new HashSet<>(selection.currentHypotheses());
        rows.visit(hypothesisQuery(scope), r -> new HypothesisIdentity(reference(r,"knowledge.hypothesis","id"),text(r,"repository_id"),text(r,"workspace_id"),
                reference(r,"portfolio.project","project_id"),reference(r,"portfolio.requirement","requirement_id"),
                reference(r,"portfolio.analysis-snapshot","analysis_snapshot_id"),text(r,"analysis_session_id")), h -> {
            boolean retain;
            if (h.snapshot() != null) {
                var analysis = analysesById.get(h.snapshot());
                if (analysis == null || !analysis.repository().equals(new BackupRepositoryKey(h.repository(),h.workspace()))
                        || !analysis.project().equals(h.project()) || !analysis.requirement().equals(h.requirement())
                        || !analysis.analysisSession().equals(h.session())) throw new IOException("Missing or foreign hypothesis analysis dependency");
                retain = analysis.retained();
            } else {
                if (h.project() != null || h.requirement() != null) throw new IOException("Incomplete hypothesis ownership");
                retain = scope.history() || selection.currentHypotheses().contains(h.id());
            }
            if (retain) {
                if (included.size() >= MAX_IDENTITIES) throw new IOException("Hypothesis dependency limit exceeded");
                included.add(h.id().value()); selected.remove(h.id());
            }
        });
        if (!selected.isEmpty()) throw new IOException("Selected hypothesis is missing, stale or outside the scope");
        return included;
    }
    private Query hypothesisQuery(BackupRowScope scope) {
        var tenant=scope.repositories("repository_id","workspace_id");
        return new Query("select id,repository_id,workspace_id,project_id,requirement_id,analysis_snapshot_id,analysis_session_id,source_node_id,target_node_id "
                + "from relation_hypothesis where " + tenant.sql() + " order by id",tenant.parameters());
    }
    private Query relationQuery(BackupRowScope scope,String table,String fields) {
        if (scope.selectedVersion()) return null;
        var tenant=scope.repositories("t.repository_id","t.workspace_id");
        return new Query("select t.id,t.repository_id,t.workspace_id,t.owner_username,t.source_node_id,t.target_node_id,t.relation_type,"
                + "a.code as source_code,b.code as target_code,"+fields+" from "+table+" t left join taxonomy_node a on a.id=t.source_node_id "
                + "left join taxonomy_node b on b.id=t.target_node_id where "+tenant.sql()+" order by t.id",tenant.parameters());
    }
    private void endpoints(BackupRowScope scope,String table,Set<String> codes) throws IOException {
        var tenant=scope.repositories("t.repository_id","t.workspace_id");
        rows.visit(new Query("select a.code as source_code,b.code as target_code from "+table+" t left join taxonomy_node a on a.id=t.source_node_id "
                + "left join taxonomy_node b on b.id=t.target_node_id where "+tenant.sql(),tenant.parameters()),
                r -> new Endpoint(text(r,"source_code"),text(r,"target_code")), e -> { addCode(codes,e.source()); addCode(codes,e.target()); });
    }
    private Map<String,NodeIdentity> catalogueClosure(BackupRowScope scope,Set<String> codes) throws IOException {
        var result = new TreeMap<String,NodeIdentity>();
        if (scope.installation()) rows.visit(nodeIdentityQuery("1=1",List.of()),this::nodeIdentity,n -> putNode(result,n));
        var pending = new TreeSet<>(codes);
        while (!pending.isEmpty()) {
            var expected = new TreeSet<>(pending); pending.clear();
            for (var query : batches("select t.id,t.code,t.parent_code,t.parent_id,p.code as linked_parent_code from taxonomy_node t "
                    + "left join taxonomy_node p on p.id=t.parent_id where t.code in ",expected,"t.id")) {
                rows.visit(query,this::nodeIdentity,n -> { putNode(result,n); expected.remove(n.code()); });
            }
            if (!expected.isEmpty()) throw new IOException("Missing catalogue dependency");
            for (var node : result.values()) if (node.parentCode() != null && !result.containsKey(node.parentCode())) pending.add(node.parentCode());
            if (pending.size() > MAX_IDENTITIES) throw new IOException("Catalogue dependency limit exceeded");
        }
        // Installation inventory may add parents even when no explicit selected code exists.
        var finished = new HashSet<String>();
        for (var node : result.values()) {
            var visited = new HashSet<String>(); var cursor = node;
            while (cursor != null && !finished.contains(cursor.code())) {
                if (!visited.add(cursor.code())) throw new IOException("Cyclic catalogue hierarchy");
                if (cursor.parentCode() == null) break;
                cursor = result.get(cursor.parentCode());
                if (cursor == null) throw new IOException("Missing catalogue parent");
            }
            finished.addAll(visited);
        }
        return result;
    }
    private Query nodeIdentityQuery(String predicate,List<?> parameters) {
        return new Query("select t.id,t.code,t.parent_code,t.parent_id,p.code as linked_parent_code from taxonomy_node t "
                + "left join taxonomy_node p on p.id=t.parent_id where "+predicate+" order by t.id",parameters);
    }
    private NodeIdentity nodeIdentity(ResultSet r) throws SQLException,IOException {
        var identity = new NodeIdentity(reference(r,"knowledge.node","id"),text(r,"code"),text(r,"parent_code"));
        if (number(r,"parent_id") != null && (text(r,"linked_parent_code") == null || !Objects.equals(identity.parentCode(),text(r,"linked_parent_code"))))
            throw new IOException("Inconsistent catalogue parent identities");
        return identity;
    }
    private void putNode(Map<String,NodeIdentity> nodes,NodeIdentity node) throws IOException {
        if (nodes.size() >= MAX_IDENTITIES && !nodes.containsKey(node.code())) throw new IOException("Catalogue dependency limit exceeded");
        nodes.put(node.code(),node);
    }
    private List<Query> nodeQueries(BackupRowScope scope,Set<String> codes) {
        return scope.selectedVersion() ? List.of() : batches("select id,code,uuid,name_en,name_de,description_en,description_de,parent_code,"
                + "taxonomy_root,node_level,dataset,external_id,source,reference,sort_order,state from taxonomy_node where code in ",codes,"id");
    }
    private static List<Query> batches(String select,Set<String> ids,String order) {
        var sorted = ids.stream().sorted().toList(); var result = new ArrayList<Query>();
        for (int start=0; start<sorted.size(); start+=200) {
            var part = sorted.subList(start,Math.min(start+200,sorted.size()));
            result.add(new Query(select+"("+String.join(",",Collections.nCopies(part.size(),"?"))+") order by "+order,part));
        }
        return result;
    }
    private static void addCode(Set<String> codes,String code) throws IOException {
        if (code == null || code.isBlank() || code.length()>1024) throw new IOException("Missing catalogue endpoint");
        if (codes.size() >= MAX_IDENTITIES && !codes.contains(code)) throw new IOException("Catalogue dependency limit exceeded");
        codes.add(code);
    }
    private static SourceRecordId parentReference(Map<String,NodeIdentity> nodes,String code) { return code == null ? null : nodes.get(code).id(); }
    private static <E extends Enum<E>> E enumeration(ResultSet row,String column,Class<E> type) throws SQLException,IOException {
        try { return Enum.valueOf(type,text(row,column)); } catch (IllegalArgumentException | NullPointerException invalid) { throw new IOException("Invalid knowledge enumeration"); }
    }
    private static String decimal(ResultSet row,String column) throws SQLException { var value=row.getBigDecimal(column); return value==null?null:value.stripTrailingZeros().toPlainString(); }
    private record NodeIdentity(SourceRecordId id,String code,String parentCode) { }
    private record Endpoint(String source,String target) { }
    private record HypothesisEndpoint(String id,String source,String target) { }
    private record HypothesisIdentity(SourceRecordId id,String repository,String workspace,SourceRecordId project,SourceRecordId requirement,SourceRecordId snapshot,String session) { }
    public enum ResumePolicy { REBUILD_FROM_CAPTURE }
    public record NodeRecord(SourceRecordId sourceId,String code,String uuid,String nameEn,String nameDe,String descriptionEn,String descriptionDe,
                             String parentCode,SourceRecordId parent,String taxonomyRoot,int level,String dataset,String externalId,String source,String reference,Long sortOrder,String state) { }
    public record RelationRecord(SourceRecordId sourceId,String repositoryId,String workspaceId,String owner,SourceRecordId sourceNode,SourceRecordId targetNode,
                                 String sourceCode,String targetCode,RelationType relationType,String description,String provenance,Long weight,boolean bidirectional) { }
    public record HypothesisRecord(SourceRecordId sourceId,String repositoryId,String workspaceId,String owner,String sourceCode,String targetCode,RelationType relationType,
                                   HypothesisStatus status,String confidence,String analysisSession,SourceRecordId project,SourceRecordId requirement,SourceRecordId analysisSnapshot,boolean appliedInCurrentAnalysis,String createdAt) { }
    public record EvidenceRecord(SourceRecordId sourceId,SourceRecordId hypothesis,String evidenceType,String summary,String fullText,String confidence,String modelName,String modelVersion,String promptVersion,String inputSnapshot,String createdAt) { }
    public record ProposalRecord(SourceRecordId sourceId,String repositoryId,String workspaceId,String owner,SourceRecordId sourceNode,SourceRecordId targetNode,String sourceCode,String targetCode,
                                 RelationType relationType,ProposalStatus status,String confidence,String rationale,String provenance,String createdAt,String reviewedAt) { }
    public record RecoveryRecord(SourceRecordId sourceId,String repositoryId,String workspaceId,String branch,String previousHead,String authoritativeCommit,String causationId,RecoveryStatus status,
                                 ResumePolicy resumePolicy,String firstObservedAt,String lastObservedAt,String completedAt) { }
    public record CoverageRecord(SourceRecordId sourceId,String legacyRequirementId,String requirementText,String nodeCode,int score,String analyzedAt) { }
}
