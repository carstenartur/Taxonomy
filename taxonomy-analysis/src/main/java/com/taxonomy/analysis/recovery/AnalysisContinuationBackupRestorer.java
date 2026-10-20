package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.backup.AnalysisBackupContributor.*;
import com.taxonomy.backup.SourceRecordId;
import com.taxonomy.dto.*;
import com.taxonomy.exchange.backup.PortableJsonPayloads;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * Imports one caller-verified aggregate into isolated staged persistence under caller-remapped scope.
 * Retained catalogue/source identifiers are inert historical evidence; this API never admits work.
 * No dispatcher, broker, provider, active claim or distributed grant is available to this owner.
 */
public final class AnalysisContinuationBackupRestorer {
    private static final int MAX_CALLS=4096, MAX_QUESTIONS=16_384, MAX_QUESTION_CHARACTERS=2_000_000;
    private static final long MAX_RUN_CHARACTERS=24_000_000;
    private final EntityManager em;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final AnalysisCheckpointPayloadCodec checkpointPayload;
    public AnalysisContinuationBackupRestorer(EntityManager em,PlatformTransactionManager transactions,ObjectMapper json) {
        this.em=Objects.requireNonNull(em);this.transactions=new TransactionTemplate(Objects.requireNonNull(transactions));this.json=Objects.requireNonNull(json);
        this.checkpointPayload=new AnalysisCheckpointPayloadCodec(json);
    }
    public void restore(ContinuationRecord source,List<QuestionRecord> questions,String targetId,WorkspaceContext target) {
        Objects.requireNonNull(source);Objects.requireNonNull(questions);Objects.requireNonNull(target);
        requireUuid(targetId);requireKind(source.sourceId(),"analysis.continuation");
        text(target.username(),160);text(target.workspaceId(),320);text(target.repositoryId(),320);text(target.currentBranch(),512);
        if(source.resumePolicy()!=ResumePolicy.REVIEW_REQUIRED || source.updatedAtEpochMillis()<0 || questions.size()>MAX_QUESTIONS
                || source.relationCallsSpent()!=null && source.relationCallsSpent()<0
                || source.relationSourceCohortSize()!=null && (source.relationSourceCohortSize()<1 || source.relationSourceCohortSize()>256))
            throw invalid();
        hash(source.inputHash()); optionalText(source.currentNode(),320);
        if(!Set.of("NEW","RUNNING","STOPPED","PAUSED","CANCELLED","COMPLETED","COMPLETED_WITH_GAPS").contains(source.sourceState()))throw invalid();
        String state=Set.of("NEW","RUNNING").contains(source.sourceState())?"STOPPED":source.sourceState();
        bounded(source.requestJson(),MAX_QUESTION_CHARACTERS);var request=json.readValue(source.requestJson(),AnalysisRequest.class);
        request.setContinuationId(targetId);request.setContinuationAction(null);request.setContinuationVersion(null);request.setContinuationQuestion(null);request.setContinuationQuestions(null);
        AnalysisResult result;
        if(source.resultJson()==null)result=new AnalysisResult(Map.of(),List.of());
        else { result=json.readValue(unpackResult(source.resultJson()),AnalysisResult.class); }
        var keys=new HashSet<String>();var ids=new HashSet<SourceRecordId>();long payload=0;
        var preparedQuestions=new ArrayList<PreparedQuestion>();
        var scores=new LinkedHashMap<String,Double>(result.getRawScores());var reasons=new LinkedHashMap<String,String>(result.getReasons());
        for(var question:questions) {
            requireKind(question.sourceId(),"analysis.question");
            if(!source.sourceId().equals(question.run()) || !ids.add(question.sourceId()) || !keys.add(question.questionKey())
                    || question.questionKey()==null || !question.questionKey().matches("(?:[a-f0-9]{64}|R[a-f0-9]{63})")
                    || question.attempts()<0 || question.attempts()>MAX_CALLS || question.startedAtEpochMillis()<0
                    || !Set.of("READY","ATTEMPT","SUCCESS","FAILED","SKIPPED","UNASSESSED","CLARIFICATION","DECOMPOSED","SUPERSEDED").contains(question.sourceState()))throw invalid();
            hash(question.inputHash());text(question.provider(),128);if(question.error()!=null)bounded(question.error(),2048);bounded(question.nodeCodesJson(),MAX_QUESTION_CHARACTERS);
            var nodes=json.readValue(question.nodeCodesJson(),String[].class);if(nodes.length>MAX_CALLS)throw invalid();for(String node:nodes)text(node,320);
            if(question.prompt()!=null)bounded(question.prompt(),MAX_QUESTION_CHARACTERS);
            if(question.detailJson()!=null)bounded(question.detailJson(),MAX_QUESTION_CHARACTERS);
            String detailError=null;
            if(question.detailJson()!=null) {
                var detail=json.readValue(question.detailJson(),LlmCallDetail.class);
                detailError=detail.getError();
                if("SUCCESS".equals(question.sourceState())) {
                    if(detail.getScores()!=null)detail.getScores().forEach(scores::putIfAbsent);
                    if(detail.getReasons()!=null)detail.getReasons().forEach(reasons::putIfAbsent);
                }
            } else if("SUCCESS".equals(question.sourceState()))throw invalid();
            String storedPrompt=checkpointPayload.encodePrompt(question.prompt());
            String storedDetail=checkpointPayload.encodeDetail(question.detailJson(),question.prompt());
            String storedNodes=checkpointPayload.encodeNodes(Arrays.asList(nodes));
            // Layout selects the decoding of both fields. Keep a valid legacy pair
            // together when compression/base64 would increase its retained size.
            long clearSize=(question.prompt()==null?0L:question.prompt().length())
                    +(question.detailJson()==null?0L:question.detailJson().length());
            long encodedSize=(storedPrompt==null?0L:storedPrompt.length())+(storedDetail==null?0L:storedDetail.length());
            if(encodedSize>clearSize) {
                storedNodes=question.nodeCodesJson();storedPrompt=question.prompt();storedDetail=question.detailJson();
            }
            payload=addStored(payload,storedPrompt);payload=addStored(payload,storedDetail);
            preparedQuestions.add(new PreparedQuestion(question,storedNodes,storedPrompt,storedDetail,detailError));
        }
        result.setRawScores(scores);result.setReasons(reasons);
        var warnings=new ArrayList<>(result.getWarnings());
        warnings.add("RESTORED_HISTORICAL_SOURCE_EVIDENCE: retained catalogue and source identifiers describe the archived analysis; review the target context before any new execution.");
        result.setWarnings(warnings);
        if(Set.of("STOPPED","PAUSED").contains(state))result.setStatus("PARTIAL");
        result.setRecovery(null); // Public reads derive a new target-bound recovery revision/count from restored rows.
        String requestJson=json.writeValueAsString(request),resultJson=packResult(json.writeValueAsString(result));
        long retained=payload;
        transactions.executeWithoutResult(status -> {
            if(em.find(AnalysisContinuationRun.class,targetId)!=null)throw new IllegalStateException("Continuation restore target already exists");
            var run=new AnalysisContinuationRun();run.id=targetId;run.username=target.username();run.workspaceId=target.workspaceId();
            run.repositoryId=target.repositoryId();run.branchName=target.currentBranch();run.inputHash=source.inputHash();run.requestJson=requestJson;run.resultJson=resultJson;
            run.state=state;run.updatedAt=System.currentTimeMillis();run.payloadCharacters=retained;run.currentNode=source.currentNode();
            run.relationCallsSpent=source.relationCallsSpent();run.relationSourceCohortSize=source.relationSourceCohortSize();em.persist(run);
            for(var prepared:preparedQuestions) {
                var sourceQuestion=prepared.source();
                var question=new AnalysisQuestionCheckpoint();question.id=targetId+":"+sourceQuestion.questionKey();question.run=run;
                question.questionKey=sourceQuestion.questionKey();question.inputHash=sourceQuestion.inputHash();question.provider=sourceQuestion.provider();
                question.nodeCodes=prepared.nodeCodes();question.detailJson=prepared.detail();question.prompt=prepared.prompt();
                question.state=sourceQuestion.sourceState();question.attempts=sourceQuestion.attempts();question.startedAt=sourceQuestion.startedAtEpochMillis();
                if("ATTEMPT".equals(question.state)) {question.state="FAILED";question.error="OUTCOME_UNCERTAIN: archive retained a started attempt without an accepted outcome; explicit review and retry or leave-open is required";}
                else if("READY".equals(question.state)) {question.state="FAILED";question.error="RESTORE_REVIEW_REQUIRED: archived ready work has no execution authority";}
                else if(sourceQuestion.error()!=null)optionalError(question,sourceQuestion.error());
                else optionalError(question,prepared.detailError());
                em.persist(question);
            }
            em.flush();
        });
    }
    private record PreparedQuestion(QuestionRecord source,String nodeCodes,String prompt,String detail,String detailError) { }
    private static String unpackResult(String value) {
        try { return PortableJsonPayloads.unpack(value,(int)MAX_RUN_CHARACTERS); }
        catch(java.io.IOException failure) {throw invalid();}
    }
    private static String packResult(String value) {
        try { return PortableJsonPayloads.pack(value,(int)MAX_RUN_CHARACTERS); }
        catch(java.io.IOException failure) {throw invalid();}
    }
    private static void optionalError(AnalysisQuestionCheckpoint q,String value) {q.error=value==null?null:value.substring(0,Math.min(2048,value.length()));}
    private static long addStored(long total,String value) {if(value==null)return total;if(value.length()>MAX_RUN_CHARACTERS-total)throw invalid();return total+value.length();}
    private static void requireKind(SourceRecordId id,String kind) {if(id==null || !kind.equals(id.kind()))throw invalid();}
    private static void requireUuid(String id) {try {if(!UUID.fromString(id).toString().equals(id))throw invalid();}catch(RuntimeException failure){throw invalid();}}
    private static void hash(String value) {if(value==null || !value.matches("[a-f0-9]{64}"))throw invalid();}
    private static void optionalText(String value,int max) {if(value!=null)text(value,max);}
    private static void text(String value,int max) {if(value==null || value.isBlank() || value.length()>max)throw invalid();}
    private static void bounded(String value,long max) {if(value==null || value.length()>max)throw invalid();}
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("Invalid typed continuation archive aggregate");}
}
