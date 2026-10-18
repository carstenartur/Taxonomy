package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.backup.AnalysisBackupContributor.QuestionRecord;
import com.taxonomy.analysis.recovery.AnalysisContinuationBackupRestorer;
import com.taxonomy.analysis.recovery.AnalysisContinuationStore;
import com.taxonomy.backup.SourceRecordId;
import com.taxonomy.dto.LlmCallDetail;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.taxonomy.analysis.cluster.AnalysisContinuationBackupRestorerTest.*;
import static org.assertj.core.api.Assertions.*;

/** The unchanged portable cleartext contract must restore complete evidence into bounded storage. */
class ContinuationCompactBackupRestoreTest {
    @Test
    void aLargeCompletePlaintextArchiveSurvivesCompactRestoreAndAFactoryRestart() {
        String prompt = "FLOOD evidence — Quelle, 水位, 🌊, \u0000, e\u0301; original constraints.\n".repeat(27_000);
        var records = records(prompt, 8);
        assertThat(records.stream().mapToLong(q -> (long) q.prompt().length() + q.detailJson().length()).sum())
                .isGreaterThan(24_000_000L);
        String id = UUID.randomUUID().toString();
        String url = "jdbc:hsqldb:mem:compact-backup-" + id + ";hsqldb.lob_compressed=true;hsqldb.lob_file_scale=1";
        try (var db = new ClusterAnalysisStoreTest.Database(url, "create", sql -> sql)) {
            new AnalysisContinuationBackupRestorer(db.em, db.transactions, JSON).restore(
                    source("RUNNING", com.taxonomy.analysis.backup.AnalysisBackupContributor.ResumePolicy.REVIEW_REQUIRED), records, id, TARGET);
            Object[] accounting = tx(db).execute(status -> (Object[]) db.em.createNativeQuery("""
                    select r.payload_characters, sum(character_length(q.prompt_text) + character_length(q.detail_json))
                      from analysis_continuation r join analysis_question_checkpoint q on q.run_id=r.id
                     where r.id=?1 group by r.payload_characters
                    """).setParameter(1, id).getSingleResult());
            assertThat(((Number) accounting[0]).longValue()).isEqualTo(((Number) accounting[1]).longValue())
                    .isLessThanOrEqualTo(24_000_000L);
            assertThat(db.sent).isEmpty();
        }
        try (var db = new ClusterAnalysisStoreTest.Database(url, "validate", sql -> sql)) {
            var store = new AnalysisContinuationStore(db.em, JSON);
            for (var record : records) {
                var detail = tx(db).execute(status -> store.detail(id, record.questionKey(), TARGET));
                assertThat(detail.getPrompt()).isEqualTo(record.prompt());
                assertThat(JSON.writeValueAsString(detail)).isEqualTo(record.detailJson());
            }
            var snapshot = tx(db).execute(status -> store.read(id, TARGET));
            assertThat(snapshot.recovery().state()).isEqualTo("STOPPED");
            assertThat(snapshot.recovery().completedCalls()).isEqualTo(records.size());
            assertThat(snapshot.clusterOperationId()).isNull();
            assertThat(db.sent).isEmpty();
        }
    }

    @Test
    void incompressibleEvidenceStillFailsTheSameStoredPayloadLimitBeforePersistence() {
        var random = new Random(170_2026);
        char[] characters = new char[1_000_000];
        // Independent BMP code units, excluding controls and surrogates: each original field fits 2M.
        for (int i = 0; i < characters.length; i++) characters[i] = (char) (0x1000 + random.nextInt(0xc000));
        var records = records(new String(characters), 12);
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            assertThatThrownBy(() -> new AnalysisContinuationBackupRestorer(db.em, db.transactions, JSON).restore(
                    source("PAUSED", com.taxonomy.analysis.backup.AnalysisBackupContributor.ResumePolicy.REVIEW_REQUIRED),
                    records, UUID.randomUUID().toString(), TARGET)).isInstanceOf(IllegalArgumentException.class);
            assertNoRows(db);
        }
    }

    @Test
    void aHighlyCompressibleOversizedDetailCannotBypassTheOriginalCleartextLimit() {
        var detail = new LlmCallDetail(); detail.setRawResponse("x".repeat(2_000_001));
        var question = new QuestionRecord(new SourceRecordId("analysis.question", SOURCE.value() + ":" + SUCCESS),
                SOURCE, SUCCESS, "d".repeat(64), "MOCK", "[\"CP\"]", JSON.writeValueAsString(detail),
                "original prompt", "SUCCESS", 1, 1);
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            assertThatThrownBy(() -> new AnalysisContinuationBackupRestorer(db.em, db.transactions, JSON).restore(
                    source("PAUSED", com.taxonomy.analysis.backup.AnalysisBackupContributor.ResumePolicy.REVIEW_REQUIRED),
                    List.of(question), UUID.randomUUID().toString(), TARGET)).isInstanceOf(IllegalArgumentException.class);
            assertNoRows(db);
        }
    }

    private static List<QuestionRecord> records(String prompt, int count) {
        var detail = new LlmCallDetail(); detail.setProvider("MOCK"); detail.setPrompt(prompt);
        detail.setScores(Map.of("CP", 75.0)); detail.setReasons(Map.of("CP", "Original reason — no truncation."));
        detail.setRawResponse("{\"result\":\"original\"}");
        String raw = JSON.writeValueAsString(detail);
        assertThat(prompt.length()).isLessThanOrEqualTo(2_000_000);
        assertThat(raw.length()).isLessThanOrEqualTo(2_000_000);
        var result = new ArrayList<QuestionRecord>();
        for (int i = 0; i < count; i++) {
            String key = "%064x".formatted(i + 1);
            result.add(new QuestionRecord(new SourceRecordId("analysis.question", SOURCE.value() + ":" + key),
                    SOURCE, key, "d".repeat(64), "MOCK", "[\"CP\"]", raw, prompt, "SUCCESS", 1, i + 1));
        }
        return result;
    }

    private static void assertNoRows(ClusterAnalysisStoreTest.Database db) {
        Long runs = tx(db).execute(status -> db.em.createQuery("select count(r) from AnalysisContinuationRun r", Long.class).getSingleResult());
        Long questions = tx(db).execute(status -> db.em.createQuery("select count(q) from AnalysisQuestionCheckpoint q", Long.class).getSingleResult());
        assertThat(runs).isZero(); assertThat(questions).isZero(); assertThat(db.sent).isEmpty();
    }
}
