package com.taxonomy.portfolio.service;

import com.taxonomy.analysis.service.*;
import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.extension.api.llm.ProviderId;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequirementIntakeMatcherTest {
    private static final String INPUT = "Respond within two seconds";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LlmProviderConfig config = mock(LlmProviderConfig.class);
    private final LlmGatewayRegistry gateways = mock(LlmGatewayRegistry.class);
    private final LlmGateway gateway = mock(LlmGateway.class);
    private final RequirementIntakeMatcher matcher = new RequirementIntakeMatcher(config, gateways, new ObjectMapper());

    @Test void configuredGatewayProducesGroundedDecisionsInsteadOfInventedTargets() {
        ready("{\"decisions\":[{\"id\":\"r1\",\"classification\":\"CHANGE\",\"rationale\":\"The allowed time changes.\",\"inputQuote\":\"two seconds\",\"candidateQuote\":\"five seconds\"}]}");
        var result = matcher.review("Respond within two seconds", List.of(candidate()), null);
        assertThat(result.status()).isEqualTo(Status.COMPLETE);
        assertThat(result.candidates().getFirst().classification()).isEqualTo(Classification.CHANGE);
        assertThat(result.candidates().getFirst().requirementId()).isEqualTo(7L);
        assertThat(result.candidates().getFirst().quotes()).containsExactly("two seconds", "five seconds");
    }

    @Test void invalidOrMissingAssessmentsNeverBecomeNoMatch() {
        for (String response : List.of("{\"decisions\":[]}",
                "{\"decisions\":[{\"id\":\"foreign\",\"classification\":\"NONE\",\"rationale\":\"No\",\"inputQuote\":\"\",\"candidateQuote\":\"\"}]}",
                "{\"decisions\":[{\"id\":\"r1\",\"classification\":\"CONFLICT\",\"rationale\":\"No\",\"inputQuote\":\"fabricated\",\"candidateQuote\":\"five seconds\"}]}")) {
            ready(response);
            assertThat(matcher.review("Respond within two seconds", List.of(candidate()), null).status()).isEqualTo(Status.FAILED);
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

    @Test void explicitNoRelationshipAndReorderedDecisionsRetainOfferedIdentityAndSourcePins() {
        var architecture = new Candidate("a2", "ARCHITECTURE_REQUIREMENT", "shared-repo", null, "main",
                null, "SHARED", null, null, "R", "Retention", "Retain records for five years", "source-hash",
                null, null, List.of(), List.of("VIEW"), "a".repeat(40), "version-4");
        ready(JSON.writeValueAsString(Map.of("decisions", List.of(
                decision("a2", "NONE", "", ""), decision("r1", "CHANGE", "two seconds", "five seconds")))));

        var result = matcher.review(INPUT, List.of(candidate(), architecture), AnalysisLanguage.GERMAN);

        assertThat(result.status()).isEqualTo(Status.COMPLETE);
        assertThat(result.candidates()).containsExactly(
                candidate().assessed(Classification.CHANGE, "Reviewed evidence", List.of("two seconds", "five seconds")),
                architecture.assessed(Classification.NONE, "Reviewed evidence", List.of()));
        assertThat(result.candidates().get(1).sourceCommit()).isEqualTo("a".repeat(40));
        assertThat(result.candidates().get(1).sourceVersion()).isEqualTo("version-4");
        verify(gateway).sendHttpRequest(contains("Explain in German."), eq("fixture"), any(LlmCompletionOptions.class));
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
        ready(answer("classification", "NONE"));
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
                Arguments.of("decisions is not an array", "{\"decisions\":{}}"),
                Arguments.of("decision has missing fields", "{\"decisions\":[{}]}"),
                Arguments.of("identity has wrong JSON type", answer("id", 7)),
                Arguments.of("classification is unknown", answer("classification", "NOVEL")),
                Arguments.of("rationale is blank", answer("rationale", " \n")),
                Arguments.of("rationale exceeds limit", answer("rationale", "r".repeat(1601))),
                Arguments.of("candidate quote is fabricated", answer("candidateQuote", "six seconds")),
                Arguments.of("input quote is required for a relationship", answer("inputQuote", "")),
                Arguments.of("candidate quote is required for a relationship", answer("candidateQuote", "")));
    }

    @ParameterizedTest
    @CsvSource({"id,500,true", "id,501,false", "inputQuote,2000,true", "inputQuote,2001,false",
            "candidateQuote,2000,true", "candidateQuote,2001,false"})
    void fieldLengthIsTheOnlyReasonToRejectAnOtherwiseGroundedOfferedDecision(String field, int length, boolean accepted) {
        String value = "q".repeat(length);
        String input = field.equals("inputQuote") ? value : INPUT;
        var offered = candidate(field.equals("id") ? value : "r1",
                field.equals("candidateQuote") ? value : candidate().text());
        ready(answer(field, value));

        var result = matcher.review(input, List.of(offered), null);

        if (accepted) {
            assertThat(result.status()).isEqualTo(Status.COMPLETE);
            assertThat(result.candidates().getFirst().id()).isEqualTo(offered.id());
            assertThat(result.candidates().getFirst().classification()).isEqualTo(Classification.CHANGE);
        } else {
            assertThat(result.status()).isEqualTo(Status.FAILED);
            assertThat(result.code()).isEqualTo("INVALID_MODEL_RESPONSE");
            assertThat(result.candidates()).containsExactly(offered);
        }
    }

    @Test void duplicateDecisionCannotReplaceCoverageOrLeakAnEarlierPartialAssessment() {
        var second = new Candidate("r2", "REQUIREMENT", "repo", "work", "draft", 2L, "P", 8L, 10L,
                "OTHER", "Other requirement", "Other text", "other-hash", null, null, List.of(), List.of("VIEW"));
        var valid = decision("r1", "CHANGE", "two seconds", "five seconds");
        ready(JSON.writeValueAsString(Map.of("decisions", List.of(valid, valid))));
        var offered = List.of(candidate(), second);

        var result = matcher.review(INPUT, offered, null);

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.code()).isEqualTo("INVALID_MODEL_RESPONSE");
        assertThat(result.candidates()).containsExactlyElementsOf(offered);
        assertThat(result.candidates()).allSatisfy(row -> assertThat(row.classification()).isNull());
    }

    @Test void exactResponseAndRationaleLimitsRemainUsable() {
        String response = answer("rationale", "r".repeat(1600));
        ready(response + " ".repeat(100_000 - response.length()));
        var result = matcher.review(INPUT, List.of(candidate()), null);
        assertThat(result.status()).isEqualTo(Status.COMPLETE);
        assertThat(result.candidates().getFirst().rationale()).hasSize(1600);
        assertThat(result.candidates().getFirst().quotes()).containsExactly("two seconds", "five seconds");
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

    private static Map<String, Object> decision(String id, String classification, String inputQuote, String candidateQuote) {
        return Map.of("id", id, "classification", classification, "rationale", "Reviewed evidence",
                "inputQuote", inputQuote, "candidateQuote", candidateQuote);
    }

    private static String answer(String field, Object value) {
        var decision = new LinkedHashMap<>(decision("r1", "CHANGE", "two seconds", "five seconds"));
        decision.put(field, value);
        return JSON.writeValueAsString(Map.of("decisions", List.of(decision)));
    }

    private void ready(String reply) {
        var provider = new ProviderId("GEMINI");
        when(config.getActiveProviderId()).thenReturn(provider);
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
