package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.backup.AnalysisBackupContributor.*;
import com.taxonomy.backup.SourceRecordId;
import com.taxonomy.dto.*;
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
    private static final int MAX_CALLS=4096, MAX_QUESTION_CHARACTERS=2_000_000;
    private static final long MAX_RUN_CHARACTERS=24_000_000;
    private final EntityManager em;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    public AnalysisContinuationBackupRestorer(EntityManager em,PlatformTransactionManager transactions,ObjectMapper json) {
        this.em=Objects.requireNonNull(em);this.transactions=new TransactionTemplate(Objects.requireNonNull(transactions));this.json=Objects.requireNonNull(json);
    }
    public void restore(ContinuationRecord source,List<QuestionRecord> questions,String targetId,WorkspaceContext target) {
        Objects.requireNonNull(source);Objects.requireNonNull(questions);Objects.requireNonNull(target);
        requireUuid(targetId);requireKind(source.sourceId(),"analysis.continuation");
        text(target.username(),160);text(target.workspaceId(),320);text(target.repositoryId(),320);text(target.currentBranch(),512);
        if(source.resumePolicy()!=ResumePolicy.REVIEW_REQUIRED || source.updatedAtEpochMillis()<0 || questions.size()>MAX_CALLS)
            throw invalid();
        hash(source.inputHash()); optionalText(source.currentNode(),320);
        if(!Set.of("NEW","RUNNING","STOPPED","PAUSED","CANCELLED","COMPLETED","COMPLETED_WITH_GAPS").contains(source.sourceState()))throw invalid();
        String state=Set.of("NEW","RUNNING").contains(source.sourceState())?"STOPPED":source.sourceState();
        bounded(source.requestJson(),MAX_QUESTION_CHARACTERS);var request=json.readValue(source.requestJson(),AnalysisRequest.class);
        request.setContinuationId(targetId);request.setContinuationAction(null);request.setContinuationVersion(null);request.setContinuationQuestion(null);request.setContinuationQuestions(null);
        AnalysisResult result;
        if(source.resultJson()==null)result=new AnalysisResult(Map.of(),List.of());
        else { bounded(source.resultJson(),MAX_RUN_CHARACTERS);result=json.readValue(source.resultJson(),AnalysisResult.class); }
        var keys=new HashSet<String>();var ids=new HashSet<SourceRecordId>();long payload=0;
        var scores=new LinkedHashMap<String,Integer>(result.getRawScores());var reasons=new LinkedHashMap<String,String>(result.getReasons());
        for(var question:questions) {
            requireKind(question.sourceId(),"analysis.question");
            if(!source.sourceId().equals(question.run()) || !ids.add(question.sourceId()) || !keys.add(question.questionKey())
                    || question.questionKey()==null || !question.questionKey().matches("(?:[a-f0-9]{64}|R[a-f0-9]{63})")
                    || question.attempts()<0 || question.attempts()>MAX_CALLS || question.startedAtEpochMillis()<0
                    || !Set.of("READY","ATTEMPT","SUCCESS","FAILED","SKIPPED","UNASSESSED","CLARIFICATION").contains(question.sourceState()))throw invalid();
            hash(question.inputHash());text(question.provider(),128);if(question.error()!=null)bounded(question.error(),2048);bounded(question.nodeCodesJson(),MAX_QUESTION_CHARACTERS);
            var nodes=json.readValue(question.nodeCodesJson(),String[].class);if(nodes.length>MAX_CALLS)throw invalid();for(String node:nodes)text(node,320);
            payload=add(payload,question.prompt());payload=add(payload,question.detailJson());
            if(question.detailJson()!=null) {
                var detail=json.readValue(question.detailJson(),LlmCallDetail.class);
                if("SUCCESS".equals(question.sourceState())) {
                    if(detail.getScores()!=null)detail.getScores().forEach(scores::putIfAbsent);
                    if(detail.getReasons()!=null)detail.getReasons().forEach(reasons::putIfAbsent);
                }
            } else if("SUCCESS".equals(question.sourceState()))throw invalid();
        }
        result.setRawScores(scores);result.setReasons(reasons);
        var warnings=new ArrayList<>(result.getWarnings());
        warnings.add("RESTORED_HISTORICAL_SOURCE_EVIDENCE: retained catalogue and source identifiers describe the archived analysis; review the target context before any new execution.");
        result.setWarnings(warnings);
        if(Set.of("STOPPED","PAUSED").contains(state))result.setStatus("PARTIAL");
        result.setRecovery(null); // Public reads derive a new target-bound recovery revision/count from restored rows.
        String requestJson=json.writeValueAsString(request),resultJson=json.writeValueAsString(result);bounded(resultJson,MAX_RUN_CHARACTERS);
        long retained=payload;
        transactions.executeWithoutResult(status -> {
            if(em.find(AnalysisContinuationRun.class,targetId)!=null)throw new IllegalStateException("Continuation restore target already exists");
            var run=new AnalysisContinuationRun();run.id=targetId;run.username=target.username();run.workspaceId=target.workspaceId();
            run.repositoryId=target.repositoryId();run.branchName=target.currentBranch();run.inputHash=source.inputHash();run.requestJson=requestJson;run.resultJson=resultJson;
            run.state=state;run.updatedAt=System.currentTimeMillis();run.payloadCharacters=retained;run.currentNode=source.currentNode();em.persist(run);
            for(var sourceQuestion:questions) {
                var question=new AnalysisQuestionCheckpoint();question.id=targetId+":"+sourceQuestion.questionKey();question.run=run;
                question.questionKey=sourceQuestion.questionKey();question.inputHash=sourceQuestion.inputHash();question.provider=sourceQuestion.provider();
                question.nodeCodes=sourceQuestion.nodeCodesJson();question.detailJson=sourceQuestion.detailJson();question.prompt=sourceQuestion.prompt();
                question.state=sourceQuestion.sourceState();question.attempts=sourceQuestion.attempts();question.startedAt=sourceQuestion.startedAtEpochMillis();
                if("ATTEMPT".equals(question.state)) {question.state="FAILED";question.error="OUTCOME_UNCERTAIN: archive retained a started attempt without an accepted outcome; explicit review and retry or leave-open is required";}
                else if("READY".equals(question.state)) {question.state="FAILED";question.error="RESTORE_REVIEW_REQUIRED: archived ready work has no execution authority";}
                else if(sourceQuestion.error()!=null)optionalError(question,sourceQuestion.error());
                else if(question.detailJson!=null)optionalError(question,json.readValue(question.detailJson,LlmCallDetail.class).getError());
                em.persist(question);
            }
            em.flush();
        });
    }
    private static void optionalError(AnalysisQuestionCheckpoint q,String value) {q.error=value==null?null:value.substring(0,Math.min(2048,value.length()));}
    private static long add(long total,String value) {if(value==null)return total;bounded(value,MAX_QUESTION_CHARACTERS);if(value.length()>MAX_RUN_CHARACTERS-total)throw invalid();return total+value.length();}
    private static void requireKind(SourceRecordId id,String kind) {if(id==null || !kind.equals(id.kind()))throw invalid();}
    private static void requireUuid(String id) {try {if(!UUID.fromString(id).toString().equals(id))throw invalid();}catch(RuntimeException failure){throw invalid();}}
    private static void hash(String value) {if(value==null || !value.matches("[a-f0-9]{64}"))throw invalid();}
    private static void optionalText(String value,int max) {if(value!=null)text(value,max);}
    private static void text(String value,int max) {if(value==null || value.isBlank() || value.length()>max)throw invalid();}
    private static void bounded(String value,long max) {if(value==null || value.length()>max)throw invalid();}
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("Invalid typed continuation archive aggregate");}
}
