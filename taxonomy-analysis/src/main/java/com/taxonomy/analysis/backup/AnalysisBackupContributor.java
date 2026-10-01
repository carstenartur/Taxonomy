package com.taxonomy.analysis.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PortableRows.Query;
import com.taxonomy.workspace.backup.BackupRowScope;
import com.taxonomy.workspace.model.RepositoryTenantIdentity;
import javax.sql.DataSource;
import java.io.IOException;
import java.util.*;
import static com.taxonomy.exchange.backup.PortableRows.*;

/** Captures persisted analysis work; worker ownership is never portable. */
public final class AnalysisBackupContributor implements BackupDataContributor {
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
        if (!scope.installation()) { draftWhere += " and username=?"; draftParameters.add(principalScope); }
        rows.write(sink,"analysis","drafts",profile,scope.selectedVersion() ? null : new Query("select id,scope_key,workspace_id,username,payload_json,created_at,updated_at from analysis_working_draft where " + draftWhere + " order by id",draftParameters),r -> {
            String tenant=text(r,"scope_key"); if (!scope.includesTenant(tenant)) throw new IOException("Analysis draft scope is inconsistent");
            String owner=text(r,"username"); String payload=text(r,"payload_json"); var document=object(payload);
            String businessText=value(document,"businessText");
            if (currentTexts.size() >= 100_000) throw new IOException("Analysis draft selection limit exceeded");
            currentTexts.put(new DraftKey(tenant,owner),textHash(businessText));
            return new DraftRecord(reference(r,"analysis.draft","id"),tenant,text(r,"workspace_id"),owner,
                    draft(document),scope.history()?payload:null,instant(r,"created_at"),instant(r,"updated_at"));
        });
        var runs = scope.repositories("r.repository_id","r.workspace_id");
        var runParameters = new ArrayList<Object>(runs.parameters());
        String runWhere=runs.sql();
        if (!scope.installation()) { runWhere += " and r.username=?"; runParameters.add(principalScope); }
        if (!scope.history()) runWhere += " and not exists (select 1 from analysis_continuation newer where newer.repository_id=r.repository_id and newer.workspace_id=r.workspace_id and newer.branch_name=r.branch_name and newer.username=r.username and (newer.updated_at>r.updated_at or (newer.updated_at=r.updated_at and newer.id>r.id)))";
        var includedRuns = new HashSet<String>();
        rows.write(sink,"analysis","continuations",profile,scope.selectedVersion()?null:new Query("select r.id,r.username,r.workspace_id,r.branch_name,r.repository_id,r.input_hash,r.request_json,r.result_json,r.state,r.updated_at,r.current_node from analysis_continuation r where "+runWhere+" order by r.id",runParameters),r -> {
            String request=text(r,"request_json"),owner=text(r,"username"),workspace=text(r,"workspace_id"),repository=text(r,"repository_id"),branch=text(r,"branch_name");
            var requestDocument=object(request);
            if (!scope.history()) {
                String tenant=new RepositoryTenantIdentity(repository,"WORKSPACE:"+workspace,branch).scopeKey();
                var key = new DraftKey(tenant,owner);
                if (currentTexts.containsKey(key) && !Objects.equals(currentTexts.get(key),textHash(value(requestDocument,"businessText")))) return null;
                if (!currentTexts.containsKey(key) && "COMPLETED".equals(text(r,"state"))) return null;
            }
            String id=text(r,"id");
            if (includedRuns.size() >= 100_000) throw new IOException("Analysis continuation selection limit exceeded");
            includedRuns.add(id);
            return new ContinuationRecord(new SourceRecordId("analysis.continuation",id),owner,repository,workspace,branch,text(r,"input_hash"),request,text(r,"result_json"),
                    text(r,"state"),ResumePolicy.REVIEW_REQUIRED,r.getLong("updated_at"),text(r,"current_node"));
        });
        rows.write(sink,"analysis","questions",profile,scope.selectedVersion()?null:new Query("select q.id,q.run_id,q.question_key,q.input_hash,q.provider,q.node_codes,q.detail_json,q.prompt_text,q.state,q.attempts,q.started_at from analysis_question_checkpoint q join analysis_continuation r on r.id=q.run_id where "+runWhere+" order by q.id",runParameters),r -> {
            String run=text(r,"run_id"); if (!includedRuns.contains(run)) return null;
            return new QuestionRecord(reference(r,"analysis.question","id"),new SourceRecordId("analysis.continuation",run),text(r,"question_key"),text(r,"input_hash"),text(r,"provider"),text(r,"node_codes"),text(r,"detail_json"),text(r,"prompt_text"),text(r,"state"),r.getInt("attempts"),r.getLong("started_at"));
        });
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
