package com.taxonomy.portfolio.service;

import com.taxonomy.analysis.service.*;
import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.extension.api.llm.ProviderId;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequirementIntakeMatcherTest {
    private static final String INPUT = "Respond within two seconds";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LlmProviderConfig config = mock(LlmProviderConfig.class);
    private final LlmGatewayRegistry gateways = mock(LlmGatewayRegistry.class);
    private final LlmGateway gateway = mock(LlmGateway.class);
    private final PromptTemplateService prompts = mock(PromptTemplateService.class);
    private final RequirementIntakeMatcher matcher = new RequirementIntakeMatcher(config, gateways, JSON, prompts);

    @Test void configuredGatewayProducesGroundedDecisionsInsteadOfInventedTargets() {
        ready(reply(Map.of("A", decision("CHANGE", 1, 1))));
        var result = matcher.review(INPUT, List.of(candidate()), null);
        assertThat(result.status()).isEqualTo(Status.COMPLETE);
        assertThat(result.candidates().getFirst().classification()).isEqualTo(Classification.CHANGE);
        assertThat(result.candidates().getFirst().requirementId()).isEqualTo(7L);
        assertThat(result.candidates().getFirst().quotes()).containsExactly(INPUT, candidate().text());
        verify(gateway).sendHttpRequest(startsWith("Edited intake instructions"), eq("fixture"), any(LlmCompletionOptions.class));
    }

    @Test void invalidOrMissingAssessmentsNeverBecomeNoMatch() {
        for (String response : List.of(reply(Map.of()),
                reply(Map.of("other", decision("NONE", 1, 1))),
                reply(Map.of("A", decision("CONFLICT", 2, 1))))) {
            ready(response);
            assertThat(matcher.review(INPUT, List.of(candidate()), null).status()).isEqualTo(Status.FAILED);
        }
    }

    @Test void noGenerativeProviderIsUnavailableAndNeverCallsGateway() {
        when(config.getActiveProviderId()).thenReturn(new ProviderId("LOCAL_ONNX"));
        var result = matcher.review("Respond within two seconds", List.of(candidate()), null);
        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.code()).isEqualTo("PROVIDER_NOT_CONFIGURED");
        verifyNoInteractions(gateways);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "mock", "replay", "unconfigured"})
    void unsupportedProviderModesPreserveManualCandidatesWithoutDispatch(String mode) {
        var provider = new ProviderId("GEMINI");
        when(config.getActiveProviderId()).thenReturn(mode.equals("missing") ? null : provider);
        when(config.isMockMode()).thenReturn(mode.equals("mock"));
        when(config.isScenarioReplay(provider)).thenReturn(mode.equals("replay"));
        when(config.isProviderConfigured(provider)).thenReturn(!mode.equals("unconfigured"));

        assertUnassessed(matcher.review(INPUT, List.of(candidate()), null), Status.UNAVAILABLE, "PROVIDER_NOT_CONFIGURED");
        verifyNoInteractions(gateways, gateway);
    }

    @Test void configuredProviderWithNoCandidatesDoesNotSpendARequest() {
        ready("unused");
        var result = matcher.review(INPUT, List.of(), null);
        assertThat(result.status()).isEqualTo(Status.COMPLETE);
        assertThat(result.code()).isEqualTo("NO_CANDIDATES");
        assertThat(result.provider()).isEqualTo("GEMINI");
        assertThat(result.candidates()).isEmpty();
        verifyNoInteractions(gateways, gateway);
    }

    @Test void modelWorkInsideATransactionIsRejectedBeforeInspectingProviderConfiguration() {
        boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> matcher.review(INPUT, List.of(candidate()), null))
                    .isInstanceOf(IllegalStateException.class).hasMessage("LLM_CALL_INSIDE_TRANSACTION");
            verifyNoInteractions(config, gateways, gateway);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
        }
    }

    @Test void separateCandidateKindsRetainOfferedIdentityAndSourcePins() {
        var architecture = new Candidate("a2", "ARCHITECTURE_REQUIREMENT", "shared-repo", null, "main",
                null, "SHARED", null, null, "R", "Retention", "Retain records for five years", "source-hash",
                null, null, List.of(), List.of("VIEW"), "a".repeat(40), "version-4");
        ready(reply(Map.of("A", decision("CHANGE", 1, 1))));
        when(gateway.extractResponseText("{}")).thenReturn(
                reply(Map.of("A", decision("CHANGE", 1, 1))), reply(Map.of("A", decision("NONE", 1, 1))));

        var result = matcher.review(INPUT, List.of(candidate(), architecture), AnalysisLanguage.GERMAN);

        assertThat(result.status()).isEqualTo(Status.COMPLETE);
        assertThat(result.candidates()).containsExactly(
                candidate().assessed(Classification.CHANGE, "Reviewed evidence", List.of(INPUT, candidate().text())),
                architecture.assessed(Classification.NONE, "", List.of()));
        assertThat(result.candidates().get(1).sourceCommit()).isEqualTo("a".repeat(40));
        assertThat(result.candidates().get(1).sourceVersion()).isEqualTo("version-4");
        verify(gateway, times(2)).sendHttpRequest(contains("Explain in German."), eq("fixture"), any(LlmCompletionOptions.class));
    }

    @ParameterizedTest
    @CsvSource({
            "ARCHITECTURE_ELEMENT,DUPLICATE", "ARCHITECTURE_ELEMENT,CHANGE", "ARCHITECTURE_ELEMENT,CONFLICT",
            "ARCHITECTURE_RELATION,DUPLICATE", "ARCHITECTURE_RELATION,CHANGE", "ARCHITECTURE_RELATION,CONFLICT"})
    void architectureObjectsCannotBeClassifiedAsExistingObligations(String kind, String classification) {
        var architecture = new Candidate("existing-object", kind, "shared-repo", null, "main",
                null, null, null, null, null, "Dispatch", "Respond within five seconds", "source-hash",
                null, null, List.of(), List.of("VIEW"), "a".repeat(40), null, "dispatch");
        ready(reply(Map.of("A", decision(classification, 1, 1))));

        var result = matcher.review(INPUT, List.of(architecture), null);

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.code()).isEqualTo("INVALID_MODEL_RESPONSE");
        assertThat(result.candidates()).containsExactly(architecture);
    }

    @Test void noMatchDetailsAreDiscardedWithoutLosingGroundedMatches() {
        var unrelated = candidate("r2", "Retain records for five years");
        var decisions = new LinkedHashMap<String, Object>();
        decisions.put("B", Map.of("classification", "NONE", "rationale", "Nothing in common",
                "inputEvidence", 1, "candidateEvidence", 1));
        decisions.put("A", decision("CHANGE", 1, 1));
        ready(reply(decisions));

        var result = matcher.review(INPUT, List.of(candidate(), unrelated), null);

        assertThat(result.status()).isEqualTo(Status.COMPLETE);
        assertThat(result.candidates()).containsExactly(
                candidate().assessed(Classification.CHANGE, "Reviewed evidence", List.of(INPUT, candidate().text())),
                unrelated.assessed(Classification.NONE, "", List.of()));
    }

    @Test void onlyEmptyEvidenceListsAcceptZeroAndCannotSupportAMatch() {
        var empty = candidate("r2", "");
        ready(reply(Map.of("A", decision("NONE", 1, 0))));

        var abstained = matcher.review(INPUT, List.of(empty), null);

        assertThat(abstained.status()).isEqualTo(Status.COMPLETE);
        assertThat(abstained.candidates()).containsExactly(empty.assessed(Classification.NONE, "", List.of()));
        var options = ArgumentCaptor.forClass(LlmCompletionOptions.class);
        verify(gateway).sendHttpRequest(anyString(), eq("fixture"), options.capture());
        JsonNode schema = JSON.valueToTree(options.getValue().schema());
        var fields = schema.at("/properties/decisions/properties/A/properties");
        assertThat(fields.at("/inputEvidence/minimum").intValue()).isEqualTo(1);
        assertThat(fields.at("/candidateEvidence/minimum").intValue()).isZero();

        ready(reply(Map.of("A", decision("CHANGE", 1, 0))));
        var unsupported = matcher.review(INPUT, List.of(empty), null);

        assertThat(unsupported.status()).isEqualTo(Status.FAILED);
        assertThat(unsupported.candidates()).containsExactly(empty);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \n\t"})
    void missingTransportBodyIsFailureAndNeverACompletedNoMatch(String raw) {
        ready("unused");
        when(gateway.sendHttpRequest(anyString(), eq("fixture"), any(LlmCompletionOptions.class))).thenReturn(raw);
        assertUnassessed(matcher.review(INPUT, List.of(candidate()), null), Status.FAILED, "PROVIDER_TRANSPORT_FAILED");
        verify(gateway, never()).extractResponseText(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"choices\":[{\"finish_reason\":\"length\"}]}",
            "{\"choices\":[{\"finish_reason\":\"content_filter\"}]}",
            "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}",
            "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}",
            "{\"candidates\":[{\"finishReason\":\"RECITATION\"}]}",
            "{\"status\":\"incomplete\"}"})
    void providerCutoffSignalsRejectEvenAnOtherwiseUsableExtractedAnswer(String raw) {
        ready(reply(Map.of("A", decision("NONE", 1, 1))));
        when(gateway.sendHttpRequest(anyString(), eq("fixture"), any(LlmCompletionOptions.class))).thenReturn(raw);
        assertUnassessed(matcher.review(INPUT, List.of(candidate()), null), Status.FAILED, "INCOMPLETE_MODEL_RESPONSE");
        verify(gateway, never()).extractResponseText(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidResponses")
    void strictResponseAndEvidenceFailuresKeepTheOriginalCandidateUnassessed(String reason, String response) {
        ready(response);
        assertUnassessed(matcher.review(INPUT, List.of(candidate()), null), Status.FAILED, "INVALID_MODEL_RESPONSE");
    }

    static Stream<Arguments> invalidResponses() {
        String validResponse = answer("classification", "CHANGE");
        return Stream.of(
                Arguments.of("no extracted answer", null),
                Arguments.of("response exceeds limit", validResponse + " ".repeat(100_001 - validResponse.length())),
                Arguments.of("root is not an object", "[]"),
                Arguments.of("root has a substituted field", "{\"other\":[]}"),
                Arguments.of("decisions is an array", "{\"decisions\":[]}"),
                Arguments.of("decision has missing fields", reply(Map.of("A", Map.of()))),
                Arguments.of("classification is unknown", answer("classification", "NOVEL")),
                Arguments.of("rationale is blank", answer("rationale", " \n")),
                Arguments.of("rationale exceeds limit", answer("rationale", "r".repeat(1601))),
                Arguments.of("inexact quoted text instead of a position", answer("candidateEvidence", "six seconds")),
                Arguments.of("input evidence is required for a relationship", answer("inputEvidence", 0)),
                Arguments.of("candidate evidence must be a listed position", answer("candidateEvidence", 2)),
                Arguments.of("candidate evidence must be integral", answer("candidateEvidence", 1.5)),
                Arguments.of("zero is invalid when a source list has evidence", reply(Map.of("A", decision("NONE", 1, 0)))));
    }

    @Test void wireLettersRetainLongPersistentIdentityAndSelectLiteralEvidenceWithinLongText() {
        String id = "opaque-id:".repeat(60);
        String text = "An opening summary. The " + "prefix ".repeat(85) + "Target evidence.";
        var offered = candidate(id, text);
        ready(reply(Map.of("A", decision("CHANGE", 1, 3))));
        var result = matcher.review(INPUT, List.of(offered), null);
        assertThat(result.status()).isEqualTo(Status.COMPLETE);
        var row = result.candidates().getFirst();
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.quotes()).hasSize(2);
        assertThat(row.quotes().get(1)).isNotEmpty().contains("Target evidence.");
        assertThat(text).contains(row.quotes().get(1));
        verify(gateway).sendHttpRequest(not(contains(id)), eq("fixture"), any(LlmCompletionOptions.class));
    }

    @Test void duplicateDecisionCannotReplaceCoverageOrLeakAnEarlierPartialAssessment() {
        var offered = List.of(candidate(), candidate("r2", "Other text"));
        String valid = JSON.writeValueAsString(decision("CHANGE", 1, 1));
        String none = JSON.writeValueAsString(decision("NONE", 1, 1));
        ready("{\"decisions\":{\"A\":" + valid + ",\"A\":" + valid + ",\"B\":" + none + "}}");

        var result = matcher.review(INPUT, offered, null);

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.code()).isEqualTo("INVALID_MODEL_RESPONSE");
        assertThat(result.candidates()).containsExactlyElementsOf(offered);
        assertThat(result.candidates()).allSatisfy(row -> assertThat(row.classification()).isNull());
    }

    @Test void malformedBatchLeavesItsRowsUnclassifiedWithoutLosingAnotherBatch() {
        var offered = new ArrayList<Candidate>();
        for (int i = 0; i < 5; i++) offered.add(candidate("r" + i, "Some evidence " + i));
        var firstBatch = Map.of("A", decision("CHANGE", 1, 1), "B", decision("NONE", 1, 1),
                "C", decision("NONE", 1, 1), "D", decision("NONE", 1, 1));
        ready(reply(firstBatch));
        when(gateway.extractResponseText("{}")).thenReturn(reply(firstBatch), reply(Map.of()));
        var result = matcher.review(INPUT, offered, null);
        assertThat(result.status()).isEqualTo(Status.PARTIAL);
        assertThat(result.code()).isEqualTo("INVALID_MODEL_RESPONSE");
        assertThat(result.candidates()).extracting(Candidate::id).containsExactly("r0", "r1", "r2", "r3", "r4");
        assertThat(result.candidates()).extracting(Candidate::classification)
                .containsExactly(Classification.CHANGE, Classification.NONE, Classification.NONE, Classification.NONE, null);
        verify(gateway, times(2)).sendHttpRequest(anyString(), eq("fixture"), any(LlmCompletionOptions.class));
    }

    @Test void revokedSourceAccessAfterFirstBatchStopsBeforeSendingMoreEvidence() {
        var offered = new ArrayList<Candidate>();
        for (int i = 0; i < 5; i++) offered.add(candidate("r" + i, "Some evidence " + i));
        var firstBatch = Map.of("A", decision("CHANGE", 1, 1), "B", decision("NONE", 1, 1),
                "C", decision("NONE", 1, 1), "D", decision("NONE", 1, 1));
        ready(reply(firstBatch));
        var candidates = mock(RequirementIntakeCandidates.class);
        var context = new WorkspaceContext("alice", "work", "draft", "repo");
        var scope = new Scope("repo", "work", "work", "draft");
        var frozen = new RequirementIntakeCandidates.Frozen(scope, null, offered, List.of(), new Coverage(5, 5, false), true);
        when(candidates.freeze(null, "alice", context)).thenReturn(frozen);
        when(candidates.discover(frozen, "alice")).thenReturn(frozen);
        var revoked = PortfolioException.notFound("Architecture is unavailable");
        doNothing().doThrow(revoked).when(candidates).recheckSources(frozen, "alice");
        var service = new RequirementIntakeService(candidates, matcher);

        assertThatThrownBy(() -> service.review(new Request(null, null, INPUT, null), "alice", context)).isSameAs(revoked);

        verify(candidates, times(3)).recheck(frozen, "alice", context);
        verify(candidates, times(2)).recheckSources(frozen, "alice");
        verify(gateway).sendHttpRequest(anyString(), eq("fixture"), any(LlmCompletionOptions.class));
    }

    @Test void exactResponseAndRationaleLimitsRemainUsable() {
        String response = answer("rationale", "r".repeat(1600));
        ready(response + " ".repeat(100_000 - response.length()));
        var result = matcher.review(INPUT, List.of(candidate()), null);
        assertThat(result.status()).isEqualTo(Status.COMPLETE);
        assertThat(result.candidates().getFirst().rationale()).hasSize(1600);
        assertThat(result.candidates().getFirst().quotes()).containsExactly(INPUT, candidate().text());
    }

    @ParameterizedTest
    @MethodSource("providerFailures")
    void providerFailureKindsStayDistinctWithoutExposingProviderDiagnosticText(RuntimeException failure, Status status, String code) {
        ready("unused");
        when(gateway.sendHttpRequest(anyString(), eq("fixture"), any(LlmCompletionOptions.class))).thenThrow(failure);
        var result = matcher.review(INPUT, List.of(candidate()), null);
        assertUnassessed(result, status, code);
        assertThat(result.provider()).isEqualTo("GEMINI");
        assertThat(result.toString()).doesNotContain("private-provider-diagnostic");
        verify(gateway, never()).extractResponseText(any());
    }

    static Stream<Arguments> providerFailures() {
        return Stream.of(
                Arguments.of(new LlmTimeoutException("private-provider-diagnostic"), Status.UNAVAILABLE, "PROVIDER_TIMEOUT"),
                Arguments.of(new LlmRateLimitException("private-provider-diagnostic"), Status.UNAVAILABLE, "PROVIDER_RATE_LIMITED"),
                Arguments.of(new IllegalStateException("private-provider-diagnostic"), Status.FAILED, "PROVIDER_REVIEW_FAILED"));
    }

    @Test void providerExtractionCanRejectAnIncompleteBodyWithoutUsingItsPartialEvidence() {
        ready("unused");
        when(gateway.extractResponseText("{}")).thenThrow(new LlmIncompleteResponseException("private partial answer", "length"));
        assertUnassessed(matcher.review(INPUT, List.of(candidate()), null), Status.FAILED, "INCOMPLETE_MODEL_RESPONSE");
    }

    private static void assertUnassessed(RequirementIntakeMatcher.Result result, Status status, String code) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.code()).isEqualTo(code);
        assertThat(result.candidates()).containsExactly(candidate());
        assertThat(result.candidates().getFirst().classification()).isNull();
        assertThat(result.candidates().getFirst().quotes()).isEmpty();
    }

    private static Map<String, Object> decision(String classification, int inputIndex, int candidateIndex) {
        String reason = classification.equals("NONE") ? "" : "Reviewed evidence";
        return Map.of("classification", classification, "rationale", reason,
                "inputEvidence", inputIndex, "candidateEvidence", candidateIndex);
    }

    private static String reply(Map<String, ?> decisions) {
        return JSON.writeValueAsString(Map.of("decisions", decisions));
    }

    private static String answer(String field, Object value) {
        var decision = new LinkedHashMap<>(decision("CHANGE", 1, 1));
        decision.put(field, value);
        return reply(Map.of("A", decision));
    }

    private void ready(String reply) {
        var provider = new ProviderId("GEMINI");
        when(config.getActiveProviderId()).thenReturn(provider);
        lenient().when(prompts.getTemplate("requirement-intake")).thenReturn("Edited intake instructions");
        when(config.isProviderConfigured(provider)).thenReturn(true);
        when(config.getApiKey(provider)).thenReturn("fixture");
        when(gateways.getGatewayById(provider)).thenReturn(gateway);
        when(gateway.sendHttpRequest(anyString(), eq("fixture"), any(LlmCompletionOptions.class))).thenReturn("{}");
        when(gateway.extractResponseText("{}")).thenReturn(reply);
    }

    static Candidate candidate() {
        return candidate("r1", "Respond within five seconds");
    }

    private static Candidate candidate(String id, String text) {
        return new Candidate(id, "REQUIREMENT", "repo", "work", "draft", 2L, "P", 7L, 9L,
                "REQ", "Response time", text, "saved-hash", null, null,
                List.of(), List.of("NEW", "CHANGE", "LINK", "VIEW"));
    }
}
