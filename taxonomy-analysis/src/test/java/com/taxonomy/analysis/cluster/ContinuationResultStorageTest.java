package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.backup.AnalysisBackupContributor.ContinuationRecord;
import com.taxonomy.analysis.backup.AnalysisBackupContributor.ResumePolicy;
import com.taxonomy.analysis.recovery.AnalysisContinuationBackupRestorer;
import com.taxonomy.analysis.recovery.AnalysisContinuationStore;
import com.taxonomy.dto.*;
import com.taxonomy.exchange.backup.PortableJsonPayloads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.taxonomy.analysis.cluster.AnalysisContinuationBackupRestorerTest.*;
import static org.assertj.core.api.Assertions.*;

/** Exercises the actual CLOB, restart, previous-relations reader and portable owner restore. */
class ContinuationResultStorageTest {
    @Test void theActualJsonParserRetainsEveryFieldAndCannotReturnAnUnverifiedFrame() throws Exception {
        var original = new AnalysisResult(Map.of("CP", 75), List.of());
        original.setReasons(Map.of("CP", "Original complete evidence 水位 🌊\n".repeat(4_000)));
        original.setRelationSearchReport(report()); original.setStatus("PARTIAL");
        original.setWarnings(List.of("Retained warning with full context"));
        String packed = PortableJsonPayloads.pack(JSON.writeValueAsString(original), 24_000_000);
        var parsed = PortableJsonPayloads.read(packed, 24_000_000,
                reader -> JSON.readValue(reader, AnalysisResult.class));
        tools.jackson.databind.JsonNode actual = JSON.valueToTree(parsed);
        tools.jackson.databind.JsonNode expected = JSON.valueToTree(original);
        assertThat(actual).isEqualTo(expected);
        String[] fields = packed.split(":", 5);
        assertThat(fields).hasSize(5);
        String broken = String.join(":", fields[0], fields[1], fields[2], "0".repeat(64), fields[4]);
        assertThatThrownBy(() -> PortableJsonPayloads.read(broken, 24_000_000,
                reader -> JSON.readValue(reader, AnalysisResult.class))).isInstanceOf(java.io.IOException.class);
    }

    @Test void fullResultBeyondTheOldCleartextCeilingSurvivesCompletionRestartAndCancellation() throws Exception {
        String reason = "Original complete evidence 水位.\n".repeat(440_000);
        var report = report();
        var result = new AnalysisResult(Map.of("CP", 75), List.of());
        result.setReasons(Map.of("CP", reason)); result.setRelationSearchReport(report); result.setStatus("SUCCESS");
        assertThat(JSON.writeValueAsString(result).length()).isGreaterThan(12_000_000);
        String id = UUID.randomUUID().toString(), url = "jdbc:hsqldb:mem:result-storage-" + id;
        AnalysisContinuationStore.Claim claim;
        try (var db = new ClusterAnalysisStoreTest.Database(url, "create", sql -> sql)) {
            var request = new AnalysisRequest("requirement"); request.setProvider("MOCK");
            request.setResumable(true); request.setContinuationId(id);
            var store = new AnalysisContinuationStore(db.em, JSON);
            claim = tx(db).execute(status -> store.begin(request, TARGET, "c".repeat(64), List.of()));
            tx(db).execute(status -> store.complete(claim, result, List.of()));
            String stored = tx(db).execute(status -> db.em.createQuery(
                    "select r.resultJson from AnalysisContinuationRun r where r.id=?1", String.class).setParameter(1, id).getSingleResult());
            assertThat(stored.length()).isLessThanOrEqualTo(12_000_000);
            var retained = (tools.jackson.databind.node.ObjectNode) JSON.readTree(PortableJsonPayloads.unpack(stored, 24_000_000));
            var returned = (tools.jackson.databind.node.ObjectNode) JSON.readTree(JSON.writeValueAsString(result));
            // Recovery versions are derived again after the final SQL flush; they are not evidence.
            retained.remove("recovery"); returned.remove("recovery");
            assertThat(retained.equals(returned)).as("every persisted evidence field survives packing").isTrue();
        }
        try (var db = new ClusterAnalysisStoreTest.Database(url, "validate", sql -> sql)) {
            var store = new AnalysisContinuationStore(db.em, JSON);
            var reopened = tx(db).execute(status -> store.read(id, TARGET));
            assertThat(reopened.result().getReasons()).containsEntry("CP", reason);
            assertThat(reopened.result().getRelationSearchReport()).isEqualTo(report);
            var previousRelations = tx(db).execute(status -> store.previousRelations(claim));
            assertThat(previousRelations).isEqualTo(report);
            var cancelled = tx(db).execute(status -> store.cancel(id, TARGET));
            assertThat(cancelled.result().getReasons()).containsEntry("CP", reason);
            assertThat(cancelled.result().getRelationSearchReport()).isEqualTo(report);
            assertThat(db.sent).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void largeLegacyAndPackedArchivesRestoreTheSameCompleteEvidence(boolean packed) throws Exception {
        String reason = "Complete archived source evidence.\n".repeat(400_000);
        var result = new AnalysisResult(Map.of("CP", 75), List.of());
        result.setReasons(Map.of("CP", reason)); result.setRelationSearchReport(report());
        String raw = JSON.writeValueAsString(result);
        assertThat(raw.length()).isGreaterThan(12_000_000);
        var original = source("PAUSED", ResumePolicy.REVIEW_REQUIRED);
        var archive = new ContinuationRecord(original.sourceId(), original.principalScope(), original.repositoryId(),
                original.workspaceId(), original.branch(), original.inputHash(), original.requestJson(),
                packed ? PortableJsonPayloads.pack(raw, 24_000_000) : raw,
                original.sourceState(), original.resumePolicy(), original.updatedAtEpochMillis(), original.currentNode());
        String id = UUID.randomUUID().toString(), url = "jdbc:hsqldb:mem:result-restore-" + id;
        try (var db = new ClusterAnalysisStoreTest.Database(url, "create", sql -> sql)) {
            new AnalysisContinuationBackupRestorer(db.em, db.transactions, JSON).restore(archive, List.of(), id, TARGET);
        }
        try (var db = new ClusterAnalysisStoreTest.Database(url, "validate", sql -> sql)) {
            var restored = tx(db).execute(status -> new AnalysisContinuationStore(db.em, JSON).read(id, TARGET));
            assertThat(restored.result().getReasons()).containsEntry("CP", reason);
            assertThat(restored.result().getRelationSearchReport()).isEqualTo(report());
            assertThat(restored.result().getWarnings()).anyMatch(w -> w.startsWith("RESTORED_HISTORICAL_SOURCE_EVIDENCE:"));
            assertThat(restored.recovery().state()).isEqualTo("PAUSED");
            assertThat(restored.clusterOperationId()).isNull(); assertThat(db.sent).isEmpty();
        }
    }

    @Test void incompressibleResultsStillRespectTheOriginalStoredCeiling() {
        char[] units = new char[12_001_000]; var random = new java.util.Random(1251);
        for (int i=0;i<units.length;i++) units[i]=(char)(0x1000+random.nextInt(0xc000));
        var tooLarge = new AnalysisResult(Map.of("CP",75),List.of());
        tooLarge.setReasons(Map.of("CP",new String(units)));
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            String id = UUID.randomUUID().toString();
            var request = new AnalysisRequest("requirement"); request.setContinuationId(id);
            var store = new AnalysisContinuationStore(db.em,JSON);
            var claim = tx(db).execute(status -> store.begin(request,TARGET,"c".repeat(64),List.of()));
            assertThatThrownBy(() -> tx(db).execute(status -> store.complete(claim,tooLarge,List.of())))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("exceeds the durable result limit");
            assertThat(tx(db).execute(status -> store.read(id,TARGET)).result().getReasons()).doesNotContainKey("CP");
        }
    }

    @Test void interruptedProviderCanStillCheckpointItsCompletePartialEvidence() {
        String reason = "Accepted before provider interruption. ".repeat(4_000);
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            String id = UUID.randomUUID().toString();
            var request = new AnalysisRequest("requirement"); request.setContinuationId(id);
            var partial = new AnalysisResult(Map.of("CP",75),List.of());
            partial.setReasons(Map.of("CP",reason)); partial.setRelationSearchReport(report());
            partial.setStatus("PARTIAL"); partial.setErrorMessage("CANCELLED: provider interrupted after accepted work");
            var store = new AnalysisContinuationStore(db.em,JSON);
            var claim = tx(db).execute(status -> store.begin(request,TARGET,"c".repeat(64),List.of()));
            try {
                tx(db).executeWithoutResult(status -> {
                    Thread.currentThread().interrupt();
                    store.completeWithFallback(claim,partial,List::of);
                });
            } finally { Thread.interrupted(); }
            var retained = tx(db).execute(status -> store.read(id,TARGET));
            assertThat(retained.recovery().state()).isEqualTo("STOPPED");
            assertThat(retained.result().getStatus()).isEqualTo("PARTIAL");
            assertThat(retained.result().getReasons()).containsEntry("CP",reason);
            assertThat(retained.result().getRelationSearchReport()).isEqualTo(report());
        }
    }

    private static RelationSearchReport report() {
        return new RelationSearchReport(2, "c".repeat(64), "fixture", List.of(),
                new RelationSearchModel.Result(List.of(), List.of(), List.of(), 0, 0, 0),
                0, 24, 1, List.of("Complete historical warning"), "");
    }
}
