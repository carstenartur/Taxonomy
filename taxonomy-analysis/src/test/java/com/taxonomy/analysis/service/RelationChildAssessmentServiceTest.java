package com.taxonomy.analysis.service;

import com.taxonomy.analysis.assessment.RelationAssessment;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RelationChildAssessmentServiceTest {
    private static final String ORIGINAL = "Authorized staff may read existing evidence.";
    private static RelationAssessment.Context context(RelationAssessment.Direction direction, String root) {
        return new RelationAssessment.Context("rev-1", ORIGINAL, root + "-source", root,
                "Read existing evidence", List.of("read existing evidence"), "CONSUMES", direction,
                RelationAssessment.Phase.NAVIGATE);
    }
    private static RelationAssessment.Candidate candidate(String id, String root) {
        return new RelationAssessment.Candidate(id, "Existing evidence", "Read-only evidence", root, false);
    }
    private static String answer(String id) {
        return "{\"" + id + "\":{\"decision\":\"ACCEPT\",\"contribution\":\"Provide existing evidence\","
                + "\"evidence\":[\"read existing evidence\"],\"reason\":\"Needed for the requested read operation\","
                + "\"question\":\"\",\"combination\":\"REQUIRED\",\"alternativeGroup\":\"\"}}";
    }
    private static RelationChildAssessmentService service(LlmService llm) {
        when(llm.getActiveProviderName()).thenReturn("test");
        return new RelationChildAssessmentService(llm, new ObjectMapper(), new RelationCompatibilityMatrix());
    }

    @Test
    void usesExistingGatewayAndKeepsPromptRawResponseAndTypedDecision() {
        var llm = mock(LlmService.class);
        String raw = "```json\n" + answer("IP-1") + "\n```";
        when(llm.callLlmRaw(anyString())).thenReturn(raw);
        var request = context(RelationAssessment.Direction.OUTGOING, "BP");
        var result = service(llm).assess(request, List.of(candidate("IP-1", "IP")));
        assertTrue(result.complete());
        assertEquals(RelationAssessment.Decision.ACCEPT, result.decisions().get("IP-1").decision());
        assertSame(request, result.context());
        assertEquals(raw, result.detail().getRawResponse());
        assertTrue(result.detail().getPrompt().contains(ORIGINAL));
        assertTrue(result.detail().getPrompt().contains("rev-1"));
        assertTrue(result.detail().getScores().isEmpty());
        verify(llm).callLlmRaw(anyString());
    }

    @Test
    void rejectsIncompatibleTargetsWithoutCallingTheModel() {
        var llm = mock(LlmService.class);
        var result = service(llm).assess(context(RelationAssessment.Direction.OUTGOING, "BP"),
                List.of(candidate("CO-1", "CO")));
        assertTrue(result.complete());
        assertFalse(result.providerCallAttempted());
        assertEquals(RelationAssessment.Decision.REJECT, result.decisions().get("CO-1").decision());
        verify(llm, never()).callLlmRaw(anyString());
    }

    @Test
    void supportsIncomingRelationsWithTheCorrectDirection() {
        var llm = mock(LlmService.class);
        when(llm.callLlmRaw(anyString())).thenReturn(answer("BP-1"));
        var result = service(llm).assess(context(RelationAssessment.Direction.INCOMING, "IP"),
                List.of(candidate("BP-1", "BP")));
        assertTrue(result.complete());
        assertEquals(RelationAssessment.Decision.ACCEPT, result.decisions().get("BP-1").decision());
        verify(llm).callLlmRaw(anyString());
    }

    @Test
    void malformedMissingForeignAndDuplicateAnswersRemainFailuresNotZeroRelevance() {
        for (String raw : List.of("please try again", "[]", "{}", answer("IP-foreign"),
                answer("IP-1").replace("\"ACCEPT\"", "\"ACCEPT\",\"decision\":\"REJECT\""))) {
            var llm = mock(LlmService.class);
            when(llm.callLlmRaw(anyString())).thenReturn(raw);
            var result = service(llm).assess(context(RelationAssessment.Direction.OUTGOING, "BP"),
                    List.of(candidate("IP-1", "IP")));
            assertFalse(result.complete(), raw);
            assertTrue(result.decisions().isEmpty(), raw);
            assertEquals(raw, result.detail().getRawResponse());
            assertNotNull(result.detail().getError());
        }
    }

    @Test
    void rejectsOversizedAndDuplicateBatchesBeforeAnyModelCall() {
        var llm = mock(LlmService.class);
        var service = service(llm);
        var request = context(RelationAssessment.Direction.OUTGOING, "BP");
        assertThrows(IllegalArgumentException.class, () -> service.assess(request,
                IntStream.range(0, 41).mapToObj(i -> candidate("IP-" + i, "IP")).toList()));
        assertThrows(IllegalArgumentException.class, () -> service.assess(request,
                List.of(candidate("IP-1", "IP"), candidate("IP-1", "IP"))));
        verify(llm, never()).callLlmRaw(anyString());
    }

    @Test
    void propagatesCooperativeCancellationInsteadOfReportingARejectedRelation() {
        var llm = mock(LlmService.class);
        when(llm.callLlmRaw(anyString())).thenThrow(new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED));
        assertThrows(AnalysisStoppedException.class, () -> service(llm).assess(
                context(RelationAssessment.Direction.OUTGOING, "BP"), List.of(candidate("IP-1", "IP"))));
    }

    @Test
    void snapshotsTheOfferedBatchBeforeCallingTheProvider() {
        var llm = mock(LlmService.class);
        var supplied = new java.util.ArrayList<>(List.of(candidate("IP-1", "IP")));
        when(llm.callLlmRaw(anyString())).thenAnswer(invocation -> {
            supplied.clear();
            return answer("IP-1");
        });
        var result = service(llm).assess(context(RelationAssessment.Direction.OUTGOING, "BP"), supplied);
        assertTrue(result.complete());
        assertEquals(List.of("IP-1"), List.copyOf(result.decisions().keySet()));
    }

}
