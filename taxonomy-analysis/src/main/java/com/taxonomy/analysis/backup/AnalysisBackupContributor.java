package com.taxonomy.analysis.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PortableRows.Query;
import com.taxonomy.workspace.backup.BackupRowScope;
import com.taxonomy.workspace.model.RepositoryTenantIdentity;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import com.taxonomy.analysis.backup.AnalysisRunSelection.Key;
import com.taxonomy.analysis.backup.AnalysisRunSelection.Run;
import static com.taxonomy.exchange.backup.PortableRows.*;

/** Captures persisted analysis work; worker ownership is never portable. */
public final class AnalysisBackupContributor implements BackupDataContributor {
    private static final String RUN_COLUMNS = "r.id as run_id,r.username,r.workspace_id,r.branch_name,r.repository_id,r.input_hash as run_input_hash,r.updated_at,r.row_version,r.state as run_state,r.current_node";
    private final PortableRows rows;
    private final String principalScope;
    public AnalysisBackupContributor(DataSource database,String principalScope) {
        rows = new PortableRows(database); this.principalScope = Objects.requireNonNull(principalScope);
    }
    @Override public BackupComponentId componentId() { return new BackupComponentId("analysis"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() { return Set.of("com.taxonomy.analysis.session.AnalysisWorkingDraft", "com.taxonomy.analysis.recovery.AnalysisContinuationRun", "com.taxonomy.analysis.recovery.AnalysisQuestionCheckpoint"); }
    @Override public List<String> omissions(BackupProfile profile) {
        if (profile == BackupProfile.SELECTED_VERSION) return List.of("analysis: drafts and continuations are not versioned by Git and cannot be attributed to a selected historical commit");
        if (!profile.includesHistory()) return List.of("analysis: stale draft results, earlier continuations and unattached historical checkpoints are excluded", "analysis: worker claims and live execution are excluded");
        return List.of("analysis: worker claims and live execution are excluded");
    }
    @Override public void write(SnapshotContext snapshot,ComponentSink sink) throws IOException {
        var scope = new BackupRowScope(snapshot); var profile = snapshot.authorization().request().profile();
        var currentTexts = new HashMap<DraftKey,String>();
        var drafts = scope.tenants("scope_key");
        var draftParameters = new ArrayList<Object>(drafts.parameters());
        String draftWhere = drafts.sql();
        String draftPrincipalScope=draftOwner(principalScope);
        if (!scope.installation()) { draftWhere += " and username=?"; draftParameters.add(draftPrincipalScope); }
        rows.write(sink,"analysis","drafts",profile,scope.selectedVersion() ? null : new Query("select id,scope_key,workspace_id,username,payload_json,created_at,updated_at from analysis_working_draft where " + draftWhere + " order by id",draftParameters),r -> {
            String tenant=text(r,"scope_key"),owner=text(r,"username"),workspace=text(r,"workspace_id");
            verifyOwner(scope,owner,draftPrincipalScope);
            try {
                var identity=RepositoryTenantIdentity.parse(tenant);
                if (!tenant.equals(identity.scopeKey()) || !scope.includesTenant(tenant)
                        || !identity.workspaceScope().equals("WORKSPACE:"+workspace))
                    throw new IOException("Analysis draft scope is inconsistent");
            } catch (IllegalArgumentException | NullPointerException invalid) { throw new IOException("Analysis draft scope is inconsistent"); }
            String payload=text(r,"payload_json"); var document=object(payload);
            String businessText=value(document,"businessText");
            if (currentTexts.size() >= 100_000) throw new IOException("Analysis draft selection limit exceeded");
            currentTexts.put(new DraftKey(tenant,owner),textHash(businessText));
            return new DraftRecord(reference(r,"analysis.draft","id"),tenant,workspace,owner,
                    draft(document),scope.history()?payload:null,instant(r,"created_at"),instant(r,"updated_at"));
        });
        var runs = scope.repositories("r.repository_id","r.workspace_id");
        var runParameters = new ArrayList<Object>(runs.parameters());
        String runWhere=runs.sql();
        if (!scope.installation()) { runWhere += " and r.username=?"; runParameters.add(principalScope); }
        // Select from metadata, never from request/result bodies or a collation-sensitive SQL anti-join.
        var selection = new AnalysisRunSelection(scope.history());
        rows.visit(scope.selectedVersion()?null:new Query("select "+RUN_COLUMNS+" from analysis_continuation r where "+runWhere+" order by r.id",runParameters),
                r -> run(r,scope),selection::accept);
        var pending = selection.selected();
        var includedRuns = new TreeMap<String,Run>();
        rows.writeBatches(sink,"analysis","continuations",profile,batches("select "+RUN_COLUMNS+",r.request_json,r.result_json from analysis_continuation r where r.id in (", " order by r.id",pending.keySet()),r -> {
            var run=run(r,scope);
            if (!run.equals(pending.remove(run.id()))) throw new IOException("Analysis continuation changed during capture");
            var key=run.key();
            String request=text(r,"request_json");
            var requestDocument=object(request);
            if (!scope.history()) {
                String tenant=new RepositoryTenantIdentity(key.repository(),"WORKSPACE:"+key.workspace(),key.branch()).scopeKey();
                var draftKey = new DraftKey(tenant,draftOwner(key.owner()));
                if (currentTexts.containsKey(draftKey) && !Objects.equals(currentTexts.get(draftKey),textHash(value(requestDocument,"businessText")))) return null;
                if (!currentTexts.containsKey(draftKey) && "COMPLETED".equals(run.state())) return null;
            }
            includedRuns.put(run.id(),run);
            return new ContinuationRecord(new SourceRecordId("analysis.continuation",run.id()),key.owner(),key.repository(),key.workspace(),key.branch(),run.inputHash(),request,text(r,"result_json"),
                    run.state(),ResumePolicy.REVIEW_REQUIRED,run.updatedAt(),run.currentNode());
        });
        if (!pending.isEmpty()) throw new IOException("Analysis continuation disappeared during capture");
        rows.writeBatches(sink,"analysis","questions",profile,batches("select "+RUN_COLUMNS+",q.id,q.run_id as question_run_id,q.question_key,q.input_hash,q.provider,q.node_codes,q.detail_json,q.prompt_text,q.state,q.attempts,q.started_at from analysis_question_checkpoint q join analysis_continuation r on r.id=q.run_id where r.id in (", " order by q.id",includedRuns.keySet()),r -> {
            var run=run(r,scope);
            if (!run.id().equals(text(r,"question_run_id")) || !run.equals(includedRuns.get(run.id())))
                throw new IOException("Analysis question reference is inconsistent");
            return new QuestionRecord(reference(r,"analysis.question","id"),new SourceRecordId("analysis.continuation",run.id()),text(r,"question_key"),text(r,"input_hash"),text(r,"provider"),text(r,"node_codes"),text(r,"detail_json"),text(r,"prompt_text"),text(r,"state"),r.getInt("attempts"),r.getLong("started_at"));
        });
    }
    private static String draftOwner(String owner) {
        // Working drafts persist WorkspaceScope.username's normalized form; runs preserve the authenticated owner.
        return owner.strip().toLowerCase(Locale.ROOT);
    }
    private static void verifyOwner(BackupRowScope scope,String owner,String expectedOwner) throws IOException {
        if (owner==null || owner.isBlank() || (!scope.installation() && !expectedOwner.equals(owner)))
            throw new IOException("Analysis owner is inconsistent");
    }
    private Run run(ResultSet row,BackupRowScope scope) throws SQLException,IOException {
        var key=new Key(text(row,"username"),text(row,"repository_id"),text(row,"workspace_id"),text(row,"branch_name"));
        verifyOwner(scope,key.owner(),principalScope);
        if (!scope.includesWorkspace(key.repository(),key.workspace())) throw new IOException("Analysis continuation scope is inconsistent");
        try {
            var tenant=new RepositoryTenantIdentity(key.repository(),"WORKSPACE:"+key.workspace(),key.branch());
            if (key.workspace()==null || !tenant.repositoryId().equals(key.repository()) || !tenant.branch().equals(key.branch())
                    || !tenant.workspaceScope().equals("WORKSPACE:"+key.workspace())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new IOException("Analysis continuation scope is inconsistent"); }
        return new Run(text(row,"run_id"),key,text(row,"run_input_hash"),row.getLong("updated_at"),row.getLong("row_version"),text(row,"run_state"),text(row,"current_node"));
    }
    private static List<Query> batches(String prefix,String suffix,Set<String> selectedIds) {
        var ids=new ArrayList<>(selectedIds); var queries=new ArrayList<Query>();
        for (int start=0;start<ids.size();start+=200) {
            var batch=ids.subList(start,Math.min(start+200,ids.size()));
            queries.add(new Query(prefix+String.join(",",Collections.nCopies(batch.size(),"?"))+")"+suffix,batch));
        }
        return queries;
    }
    private static JsonNode object(String value) throws IOException {
        if (value==null) throw new IOException("Missing analysis document");
        var document=json().readTree(value);
        if (document==null || !document.isObject()) throw new IOException("Invalid analysis document");
        return document;
    }
    private static String value(JsonNode node,String field) throws IOException {
        var value=node.get(field);
        if (value==null || value.isNull()) return null;
        if (!value.isTextual()) throw new IOException("Invalid analysis text field");
        return value.textValue();
    }
    private static DraftPayload draft(JsonNode node) throws IOException {
        String current=value(node,"businessText");
        boolean analyzed=current!=null && current.equals(value(node,"lastAnalyzedText"));
        return new DraftPayload(1,value(node,"draftState"),current,value(node,"currentView"),node.get("analysisOptions"),
                analyzed?current:null,Objects.equals(current,value(node,"storedBusinessText"))?current:null,
                analyzed?node.get("scores"):null,analyzed?node.get("reasons"):null,analyzed?node.get("discrepancies"):null,
                analyzed?node.get("architectureView"):null,analyzed?node.get("provisionalRelations"):null,analyzed?node.get("evaluatedNodes"):null);
    }
    private static String textHash(String value) {
        if (value == null) return null;
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private record DraftKey(String tenant,String principalScope) { }
    public enum ResumePolicy { REVIEW_REQUIRED }
    public record DraftPayload(int schemaVersion,String draftState,String businessText,String currentView,JsonNode analysisOptions,
                               String lastAnalyzedText,String storedBusinessText,JsonNode scores,JsonNode reasons,JsonNode discrepancies,
                               JsonNode architectureView,JsonNode provisionalRelations,JsonNode evaluatedNodes) { }
    public record DraftRecord(SourceRecordId sourceId,String tenant,String workspaceId,String principalScope,DraftPayload payload,String historicalPayload,String createdAt,String updatedAt) { }
    public record ContinuationRecord(SourceRecordId sourceId,String principalScope,String repositoryId,String workspaceId,String branch,String inputHash,
                                     String requestJson,String resultJson,String sourceState,ResumePolicy resumePolicy,long updatedAtEpochMillis,String currentNode) { }
    public record QuestionRecord(SourceRecordId sourceId,SourceRecordId run,String questionKey,String inputHash,String provider,String nodeCodesJson,
                                 String detailJson,String prompt,String sourceState,int attempts,long startedAtEpochMillis) { }
}
