package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class OnnxReferenceCasesTest {
    @Test void computesHandCheckedRecallAndFirstRank() {
        var result = OnnxReferenceCases.measure(Set.of("A", "B"), List.of("X", "B", "A"), 2);
        assertEquals(0.5, result.recall());
        assertEquals(2, result.firstRelevantRank());
        assertEquals(List.of("A"), result.missing());
    }
    @Test void emptySuccessfulLexicalResultIsMeasuredZeroNotUnavailable() {
        var result = OnnxReferenceCases.measure(Set.of("A"), List.of(), 10);
        assertEquals(0.0, result.recall());
        assertNull(result.firstRelevantRank());
    }
    @Test void duplicatePredictionsAndUndefinedReferenceAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OnnxReferenceCases.measure(Set.of("A"), List.of("A", "A"), 10));
        assertThrows(IllegalArgumentException.class,
                () -> OnnxReferenceCases.measure(Set.of(), List.of("A"), 10));
        assertThrows(IllegalArgumentException.class,
                () -> OnnxReferenceCases.measure(Set.of("A"), List.of("A"), 0));
    }
    @Test void requiredReferencesDoNotBecomeCompletePrecisionGroundTruth() {
        var result = OnnxReferenceCases.measure(Set.of("A"), List.of("A", "OTHER"), 10);
        assertEquals(1.0, result.recall());
        assertTrue(result.missing().isEmpty());
        assertEquals(List.of("A", "OTHER"), result.predicted());
    }
    @Test void resultsAreImmutableAndReferenceOrderingIsStable() {
        var result = OnnxReferenceCases.measure(Set.of("C", "A", "B"), List.of("B"), 10);
        assertEquals(List.of("A", "C"), result.missing());
        assertThrows(UnsupportedOperationException.class, () -> result.predicted().clear());
    }
    @Test void bundledCasesAreIndependentPairedRequirements() throws Exception {
        var cases = OnnxReferenceCases.load();
        assertEquals(6, cases.size());
        assertEquals(Set.of("de", "en"), cases.stream().map(OnnxReferenceCases.Case::language)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(6, cases.stream().map(OnnxReferenceCases.Case::id).distinct().count());
        assertTrue(cases.stream().allMatch(c -> c.required().size() == 1));
    }
    @Test void enabledIsNotModelOrIndexReadiness() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var status = mapper.createObjectNode().put("enabled", true).put("available", true);
        assertFalse(OnnxReferenceCases.ready(status));
        status.put("semanticReady", true).put("modelAvailable", true).put("indexState", "READY")
                .put("indexedNodesAtReadiness", 3);
        assertTrue(OnnxReferenceCases.ready(status));
        status.put("indexState", "FAILED");
        assertFalse(OnnxReferenceCases.ready(status));
    }
    @Test void usablePartialNodeIndexDoesNotWaitForRelationRecovery() {
        assertTrue(OnnxReferenceCases.ready(readyStatus("PARTIAL")));
    }

    @Test void nodeRetrievalCanStartWhileRelationsAreStillIndexing() {
        assertTrue(OnnxReferenceCases.ready(readyStatus("INDEXING_RELATIONS")));
    }

    @Test void unavailableOrUnknownStatesCannotOverrideTheReadinessGuards() {
        for (String state : List.of("DISABLED", "WAITING_FOR_TAXONOMY", "LOADING_MODEL",
                "INDEXING_NODES", "FAILED", "UNKNOWN", "")) {
            assertFalse(OnnxReferenceCases.ready(readyStatus(state)), state);
        }
        var missing = readyStatus("READY");
        missing.remove("indexState");
        assertFalse(OnnxReferenceCases.ready(missing));
    }

    @Test void partialReadinessStillRequiresEveryModelAndNodeGuard() {
        for (String state : List.of("READY", "PARTIAL", "INDEXING_RELATIONS")) {
            for (String field : List.of("enabled", "available", "modelAvailable", "semanticReady")) {
                var status = readyStatus(state);
                status.put(field, false);
                assertFalse(OnnxReferenceCases.ready(status), state + ": " + field);
                status.remove(field);
                assertFalse(OnnxReferenceCases.ready(status), state + ": missing " + field);
                status.put(field, "true");
                assertFalse(OnnxReferenceCases.ready(status), state + ": nonboolean " + field);
            }
            for (long count : new long[]{0, -1}) {
                assertFalse(OnnxReferenceCases.ready(readyStatus(state).put("indexedNodesAtReadiness", count)));
            }
            var status = readyStatus(state);
            status.remove("indexedNodesAtReadiness");
            assertFalse(OnnxReferenceCases.ready(status));
            status.put("indexedNodesAtReadiness", "3");
            assertFalse(OnnxReferenceCases.ready(status));
            status.put("indexedNodesAtReadiness", 3.5);
            assertFalse(OnnxReferenceCases.ready(status));
        }
    }

    private static tools.jackson.databind.node.ObjectNode readyStatus(String state) {
        return new tools.jackson.databind.ObjectMapper().createObjectNode()
                .put("enabled", true).put("available", true).put("modelAvailable", true)
                .put("semanticReady", true).put("indexState", state).put("indexedNodesAtReadiness", 3);
    }
}
