package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.backup.AnalysisBackupContributor.ContinuationRecord;
import com.taxonomy.analysis.backup.AnalysisBackupContributor.QuestionRecord;
import com.taxonomy.analysis.backup.AnalysisBackupContributor.ResumePolicy;
import com.taxonomy.analysis.recovery.AnalysisContinuationBackupRestorer;
import com.taxonomy.analysis.recovery.AnalysisContinuationStore;
import com.taxonomy.backup.SourceRecordId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.taxonomy.analysis.cluster.AnalysisContinuationBackupRestorerTest.*;
import static org.assertj.core.api.Assertions.*;

class RelationCallLedgerBackupTest {
    @Test void cumulativeCallSpendingSurvivesPortableSerializationRestoreAndFactoryRestart() {
        String id = UUID.randomUUID().toString(), url = "jdbc:hsqldb:mem:restored-relation-ledger-" + id;
        var source = withLedger(17);
        var serialized = JSON.readValue(JSON.writeValueAsString(source), ContinuationRecord.class);
        assertThat(JSON.readTree(JSON.writeValueAsString(serialized)).get("relationCallsSpent").asInt()).isEqualTo(17);
        try (var db = new ClusterAnalysisStoreTest.Database(url, "create", sql -> sql)) {
            new AnalysisContinuationBackupRestorer(db.em, db.transactions, JSON).restore(serialized, questions(), id, TARGET);
            assertThat(db.sent).isEmpty();
        }
        try (var db = new ClusterAnalysisStoreTest.Database(url, "validate", sql -> sql)) {
            var spent = tx(db).execute(status -> (Number) db.em.createNativeQuery(
                    "select relation_calls_spent from analysis_continuation where id=?1").setParameter(1, id).getSingleResult());
            assertThat(spent.intValue()).isEqualTo(17);
            var cohort = tx(db).execute(status -> (Number) db.em.createNativeQuery(
                    "select relation_source_cohort_size from analysis_continuation where id=?1").setParameter(1, id).getSingleResult());
            assertThat(cohort.intValue()).isEqualTo(32);
            var saved = tx(db).execute(status -> new AnalysisContinuationStore(db.em, JSON).read(id, TARGET));
            assertThat(saved.recovery().state()).isEqualTo("STOPPED");
            assertThat(saved.recovery().openQuestions()).hasSize(1); assertThat(db.sent).isEmpty();
            assertThat(saved.result().getRelationSearchReport().budget()).isEqualTo(
                    new com.taxonomy.dto.RelationSearchBudget(2, 20, 2, 17, true, 1, 32, 512, 32));
        }
    }

    @Test void aNegativeArchivedLedgerIsRejectedBeforeAnyOwnerWrites() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var source = withLedger(-1);
            assertThatThrownBy(() -> new AnalysisContinuationBackupRestorer(db.em, db.transactions, JSON)
                    .restore(source, questions(), UUID.randomUUID().toString(), TARGET)).isInstanceOf(IllegalArgumentException.class);
            assertThat((Long) tx(db).execute(status -> db.em.createQuery("select count(r) from AnalysisContinuationRun r", Long.class)
                    .getSingleResult())).isZero();
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 257})
    void invalidArchivedSourceCohortsAreRejectedBeforeAnyOwnerWrites(int cohort) {
        var tree = (tools.jackson.databind.node.ObjectNode) JSON.readTree(JSON.writeValueAsString(withLedger(17)));
        tree.put("relationSourceCohortSize", cohort);
        var source = JSON.readValue(JSON.writeValueAsString(tree), ContinuationRecord.class);
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            assertThatThrownBy(() -> new AnalysisContinuationBackupRestorer(db.em, db.transactions, JSON)
                    .restore(source, questions(), UUID.randomUUID().toString(), TARGET)).isInstanceOf(IllegalArgumentException.class);
            assertThat((Long) tx(db).execute(status -> db.em.createQuery("select count(r) from AnalysisContinuationRun r", Long.class)
                    .getSingleResult())).isZero();
        }
    }

    @Test void archiveQuestionCapacityMatchesTheCombinedScoringAndRelationJournal() {
        var sample = questions().getFirst();
        var records = new ArrayList<QuestionRecord>();
        for (int i = 0; i < 4097; i++) {
            String key = "%064x".formatted(i);
            records.add(new QuestionRecord(new SourceRecordId("analysis.question", SOURCE.value() + ":" + key),
                    SOURCE, key, sample.inputHash(), sample.provider(), sample.nodeCodesJson(), sample.detailJson(),
                    sample.prompt(), "SUCCESS", 1, 1));
        }
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            String id = UUID.randomUUID().toString();
            assertThatCode(() -> new AnalysisContinuationBackupRestorer(db.em, db.transactions, JSON)
                    .restore(source("STOPPED", ResumePolicy.REVIEW_REQUIRED), records, id, TARGET)).doesNotThrowAnyException();
            assertThat((Long) tx(db).execute(status -> db.em.createQuery("select count(q) from AnalysisQuestionCheckpoint q where q.run.id=:id", Long.class)
                    .setParameter("id", id).getSingleResult())).isEqualTo(4097);
        }
    }

    private static ContinuationRecord withLedger(int spent) {
        assertThat(ContinuationRecord.class.getRecordComponents()).extracting(RecordComponent::getName)
                .as("The portable continuation must retain spending and original prompt grouping")
                .contains("relationCallsSpent", "relationSourceCohortSize");
        var tree = (tools.jackson.databind.node.ObjectNode) JSON.readTree(JSON.writeValueAsString(source("RUNNING", ResumePolicy.REVIEW_REQUIRED)));
        tree.put("relationCallsSpent", spent);
        tree.put("relationSourceCohortSize", 32);
        if (spent >= 2) {
            var result = JSON.readValue(tree.get("resultJson").asText(), com.taxonomy.dto.AnalysisResult.class);
            var report = new com.taxonomy.dto.RelationSearchReport(2, "a".repeat(64), "policy", List.of(),
                    new com.taxonomy.dto.RelationSearchModel.Result(List.of(), List.of(), List.of(), 0, 0, 0),
                    2, 2, 0, List.of(), "");
            result.setRelationSearchReport(report.withBudget(new com.taxonomy.dto.RelationSearchBudget(
                    2, 20, 2, spent, true, 1, 32, 512, 32)));
            tree.put("resultJson", JSON.writeValueAsString(result));
        }
        return JSON.readValue(JSON.writeValueAsString(tree), ContinuationRecord.class);
    }
}
