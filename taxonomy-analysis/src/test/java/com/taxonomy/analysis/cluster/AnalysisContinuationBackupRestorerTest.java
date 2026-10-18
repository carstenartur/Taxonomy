package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.backup.AnalysisBackupContributor.*;
import com.taxonomy.analysis.recovery.*;
import com.taxonomy.backup.SourceRecordId;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Typed owner import into caller-staged persistence; no broker or provider can be resumed. */
class AnalysisContinuationBackupRestorerTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final SourceRecordId SOURCE = new SourceRecordId("analysis.continuation", UUID.randomUUID().toString());
    static final String SUCCESS = "a".repeat(64), UNCERTAIN = "R" + "b".repeat(63);
    static final WorkspaceContext TARGET = new WorkspaceContext("target-owner", "target-workspace", "target-branch", "target-repository");

    @Test void interruptedArchiveRetainsAnswersAndUncertaintyAcrossANewFactoryWithoutRuntimeAuthority() {
        String id = UUID.randomUUID().toString(), url = "jdbc:hsqldb:mem:restored-continuation-" + id;
        try (var db = new ClusterAnalysisStoreTest.Database(url, "create", sql -> sql)) {
            new AnalysisContinuationBackupRestorer(db.em, db.transactions, JSON).restore(source("RUNNING", ResumePolicy.REVIEW_REQUIRED), questions(), id, TARGET);
            var snapshot = tx(db).execute(status -> new AnalysisContinuationStore(db.em, JSON).read(id, TARGET));
            assertThat(snapshot.recovery().id()).isEqualTo(id); assertThat(snapshot.recovery().state()).isEqualTo("STOPPED");
            assertThat(snapshot.recovery().completedCalls()).isEqualTo(1);
            assertThat(snapshot.recovery().openQuestions()).singleElement().satisfies(q -> {
                assertThat(q.key()).isEqualTo(UNCERTAIN); assertThat(q.outcomeUncertain()).isTrue();
                assertThat(q.error()).contains("OUTCOME_UNCERTAIN");
            });
            assertThat(snapshot.result().getRawScores()).containsEntry("CP", 75d);
            assertThat(snapshot.result().getWarnings()).anyMatch(w -> w.startsWith("RESTORED_HISTORICAL_SOURCE_EVIDENCE"));
            assertThat(snapshot.request().getContinuationId()).isEqualTo(id);
            assertThat(snapshot.request().getContinuationAction()).isNull();
            assertThat(snapshot.request().getContinuationVersion()).isNull(); assertThat(snapshot.request().getContinuationQuestion()).isNull();
            assertThat(snapshot.clusterOperationId()).isNull(); assertThat(db.sent).isEmpty();
            tx(db).executeWithoutResult(status -> {
                Object[] run = (Object[]) db.em.createNativeQuery("select claim_token,claim_until,cluster_operation_id,cluster_source_json,cluster_catalogue_hash from analysis_continuation where id=?1").setParameter(1,id).getSingleResult();
                assertThat(run[0]).isNull(); assertThat(((Number) run[1]).longValue()).isZero(); assertThat(Arrays.asList(run).subList(2,5)).containsOnlyNulls();
                Object[] question = (Object[]) db.em.createNativeQuery("select state,attempt_owner,cluster_operation_id,cluster_task_id,cluster_delivery from analysis_question_checkpoint where id=?1").setParameter(1,id+":"+UNCERTAIN).getSingleResult();
                assertThat(question[0]).isEqualTo("FAILED"); assertThat(Arrays.asList(question).subList(1,5)).containsOnlyNulls();
                assertThatThrownBy(() -> new AnalysisContinuationStore(db.em,JSON).prepare(new AnalysisContinuationStore.Claim(id,UUID.randomUUID().toString(),null),
                        new AnalysisCheckpointSession.Question(SUCCESS,"c".repeat(64),"MOCK",List.of("CP"),"prompt")))
                        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            });
        }
        try (var db = new ClusterAnalysisStoreTest.Database(url, "validate", sql -> sql)) {
            var snapshot = tx(db).execute(status -> new AnalysisContinuationStore(db.em,JSON).read(id,TARGET));
            assertThat(snapshot.recovery().completedCalls()).isEqualTo(1); assertThat(snapshot.recovery().openQuestions().getFirst().outcomeUncertain()).isTrue();
            assertThat(db.sent).isEmpty();
            assertThatThrownBy(() -> tx(db).execute(status -> new AnalysisContinuationStore(db.em,JSON).read(id,
                    new WorkspaceContext("archive-owner",TARGET.workspaceId(),TARGET.currentBranch(),TARGET.repositoryId()))))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
    }

    @Test void supersededCorrectiveEvidenceRestoresWithoutAnOpenQuestionOrCompletedCall() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            String id = UUID.randomUUID().toString();
            var detail = new LlmCallDetail(); detail.setRawResponse("unchanged failed correction"); detail.setError("invalid quote");
            var retired = new QuestionRecord(new SourceRecordId("analysis.question", SOURCE.value() + ":" + UNCERTAIN),
                    SOURCE, UNCERTAIN, "e".repeat(64), "MOCK", "[\"IP\"]", JSON.writeValueAsString(detail),
                    "corrective prompt", "SUPERSEDED", 1, 1);
            new AnalysisContinuationBackupRestorer(db.em, db.transactions, JSON).restore(
                    source("COMPLETED", ResumePolicy.REVIEW_REQUIRED), List.of(retired), id, TARGET);
            var view = tx(db).execute(status -> new AnalysisContinuationStore(db.em, JSON).read(id, TARGET));
            assertThat(view.recovery().openQuestions()).isEmpty();
            assertThat(view.recovery().completedCalls()).isZero();
            tx(db).executeWithoutResult(status -> {
                String state = db.em.createQuery("select q.state from AnalysisQuestionCheckpoint q where q.id=?1", String.class)
                        .setParameter(1, id + ":" + UNCERTAIN).getSingleResult();
                assertThat(state).isEqualTo("SUPERSEDED");
                var restored = new AnalysisContinuationStore(db.em, JSON).detail(id, UNCERTAIN, TARGET);
                assertThat(restored.getRawResponse()).isEqualTo(detail.getRawResponse());
                assertThat(restored.getError()).isEqualTo(detail.getError());
            });
        }
    }

    @Test void parentClosureAndQuestionUniquenessFailBeforeAnyOwnerWrites() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var importer = new AnalysisContinuationBackupRestorer(db.em,db.transactions,JSON); String id=UUID.randomUUID().toString();
            var original=questions().getFirst();
            var foreign=new QuestionRecord(original.sourceId(),new SourceRecordId("analysis.continuation",UUID.randomUUID().toString()),original.questionKey(),original.inputHash(),original.provider(),original.nodeCodesJson(),original.detailJson(),original.prompt(),original.sourceState(),original.attempts(),original.startedAtEpochMillis());
            assertThatThrownBy(() -> importer.restore(source("RUNNING",ResumePolicy.REVIEW_REQUIRED),List.of(foreign),id,TARGET)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> importer.restore(source("RUNNING",ResumePolicy.REVIEW_REQUIRED),List.of(original,original),id,TARGET)).isInstanceOf(IllegalArgumentException.class);
            Long count=tx(db).execute(status -> db.em.createQuery("select count(r) from AnalysisContinuationRun r",Long.class).getSingleResult()); assertThat(count).isZero();
        }
    }

    @Test void aPreviouslyPausedUncertainQuestionRetainsTheExplicitUnknownOutcome() {
        try(var db=new ClusterAnalysisStoreTest.Database()) {
            String id=UUID.randomUUID().toString();var old=questions().getLast();
            var failed=new QuestionRecord(old.sourceId(),old.run(),old.questionKey(),old.inputHash(),old.provider(),old.nodeCodesJson(),null,old.prompt(),"FAILED",old.attempts(),old.startedAtEpochMillis(),"OUTCOME_UNCERTAIN: saved worker death");
            new AnalysisContinuationBackupRestorer(db.em,db.transactions,JSON).restore(source("PAUSED",ResumePolicy.REVIEW_REQUIRED),List.of(failed),id,TARGET);
            var view=tx(db).execute(status->new AnalysisContinuationStore(db.em,JSON).read(id,TARGET));
            assertThat(view.recovery().openQuestions()).singleElement().satisfies(q->{assertThat(q.outcomeUncertain()).isTrue();assertThat(q.error()).isEqualTo(failed.error());});
        }
    }

    @Test void oversizedRetainedQuestionIsRejectedBeforePersistence() {
        try(var db=new ClusterAnalysisStoreTest.Database()) {
            var old=questions().getFirst();String id=UUID.randomUUID().toString();
            var oversized=new QuestionRecord(old.sourceId(),old.run(),old.questionKey(),old.inputHash(),old.provider(),old.nodeCodesJson(),old.detailJson(),"x".repeat(2_000_001),old.sourceState(),old.attempts(),old.startedAtEpochMillis());
            assertThatThrownBy(()->new AnalysisContinuationBackupRestorer(db.em,db.transactions,JSON).restore(source("PAUSED",ResumePolicy.REVIEW_REQUIRED),List.of(oversized),id,TARGET)).isInstanceOf(IllegalArgumentException.class);
            Long count=tx(db).execute(status->db.em.createQuery("select count(r) from AnalysisContinuationRun r",Long.class).getSingleResult());assertThat(count).isZero();
        }
    }

    @Test void aMissingReviewPolicyIsRejectedAndPausedEvidenceRemainsPaused() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var importer=new AnalysisContinuationBackupRestorer(db.em,db.transactions,JSON);String id=UUID.randomUUID().toString();
            assertThatThrownBy(() -> importer.restore(source("PAUSED",null),questions(),id,TARGET)).isInstanceOf(IllegalArgumentException.class);
            importer.restore(source("PAUSED",ResumePolicy.REVIEW_REQUIRED),questions(),id,TARGET);
            assertThat(tx(db).execute(status -> new AnalysisContinuationStore(db.em,JSON).read(id,TARGET)).recovery().state()).isEqualTo("PAUSED");
        }
    }

    static org.springframework.transaction.support.TransactionTemplate tx(ClusterAnalysisStoreTest.Database db) { return new org.springframework.transaction.support.TransactionTemplate(db.transactions); }
    static ContinuationRecord source(String state,ResumePolicy policy) {
        var request=new AnalysisRequest("requirement");request.setProvider("MOCK");request.setResumable(true);request.setContinuationId(SOURCE.value());
        request.setContinuationAction("RETRY");request.setContinuationVersion(8L);request.setContinuationQuestion(UNCERTAIN);
        var result=new AnalysisResult(Map.of("CP",75),List.of());result.setStatus("IN_PROGRESS");result.setRecovery(new AnalysisRecoveryView(SOURCE.value(),8,state,1,2,null,List.of()));
        return new ContinuationRecord(SOURCE,"archive-owner","archive-repository","archive-workspace","archive-branch","c".repeat(64),JSON.writeValueAsString(request),JSON.writeValueAsString(result),state,policy,1,"CP");
    }
    static List<QuestionRecord> questions() {
        var detail=new LlmCallDetail();detail.setProvider("MOCK");detail.setScores(Map.of("CP",75));
        return List.of(new QuestionRecord(new SourceRecordId("analysis.question",SOURCE.value()+":"+SUCCESS),SOURCE,SUCCESS,"d".repeat(64),"MOCK","[\"CP\"]",JSON.writeValueAsString(detail),"accepted prompt","SUCCESS",1,1),
                new QuestionRecord(new SourceRecordId("analysis.question",SOURCE.value()+":"+UNCERTAIN),SOURCE,UNCERTAIN,"e".repeat(64),"MOCK","[\"IP\"]",null,"uncertain prompt","ATTEMPT",1,1));
    }
}
