package com.taxonomy.analysis.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.analysis.controller.AnalysisSseEventMapper;
import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.analysis.usecase.AnalysisStreamEvent;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class RequirementNodeAssessmentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOCUMENT = """
            {"nodeAssessments":{"BP-2":[{"requirementQuote":"  Use the archive.  ",
            "subjectKinds":["SERVICE","PRODUCT","SERVICE"],"requirementKinds":["FUNCTIONAL"],
            "contributionType":"PROVIDES","evidenceStatus":"SUPPORTED","evidenceBasis":"EXPLICIT",
            "necessity":"REQUIRED","scope":"DELIVERY","condition":"","rationale":"Protects the data.","question":""}]}}
            """;

    @Test
    void normalResultSerialisationRetainsVerbatimQuotesAndDeduplicatesKinds() {
        var result = JSON.readValue(DOCUMENT, AnalysisResult.class);
        var wire = JSON.readTree(JSON.writeValueAsString(result)).path("nodeAssessments").path("BP-2").path(0);
        assertEquals("  Use the archive.  ", wire.path("requirementQuote").asText());
        assertEquals("SERVICE", wire.path("subjectKinds").path(0).asText());
        assertEquals("PRODUCT", wire.path("subjectKinds").path(1).asText());
        assertEquals(2, wire.path("subjectKinds").size());
    }

    @Test
    void incrementalStreamRetainsStructuredEvidenceAfterDiagnosticTruncation() {
        var call = JSON.readValue(DOCUMENT, LlmCallDetail.class);
        call.setRawResponse("x".repeat(40_000));
        var accumulator = new LlmDetailAccumulator();
        accumulator.add(call);
        var merged = accumulator.result();
        assertTrue(merged.getRawResponse().contains("[diagnostic transcript truncated]"));
        var event = new AnalysisSseEventMapper().map(new AnalysisStreamEvent.Scores(Map.of(), Map.of(), "", merged));
        var claims = JSON.valueToTree(event.payload()).path("nodeAssessments").path("BP-2");
        assertEquals("SUPPORTED", claims.path(0).path("evidenceStatus").asText());
    }

    @Test
    void terminalErrorRetainsCompletedStructuredEvidenceAfterACooperativeStop() {
        var call = JSON.readValue(DOCUMENT, LlmCallDetail.class);
        var stopped = new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED).withCompletedCall(call);
        var event = new AnalysisSseEventMapper().map(new AnalysisStreamEvent.Error(
                "PARTIAL", stopped.getMessage(), stopped.partialScores(), List.of(), List.of(), List.of(),
                stopped.partialReasons(), 0L, null, stopped.partialNodeAssessments()));
        assertEquals("error", event.name());
        var claims = JSON.valueToTree(event.payload()).path("nodeAssessments").path("BP-2");
        assertEquals("  Use the archive.  ", claims.path(0).path("requirementQuote").asString());
    }

    @Test
    void checkpointCallSerialisationRetainsTheAssessment() {
        var call = JSON.readValue(DOCUMENT, LlmCallDetail.class);
        var roundTrip = JSON.readValue(JSON.writeValueAsString(AnalysisCheckpointSession.copy(call)), LlmCallDetail.class);
        var wire = JSON.readTree(JSON.writeValueAsString(roundTrip)).path("nodeAssessments").path("BP-2").path(0);
        assertEquals("SUPPORTED", wire.path("evidenceStatus").asText());
    }
}
