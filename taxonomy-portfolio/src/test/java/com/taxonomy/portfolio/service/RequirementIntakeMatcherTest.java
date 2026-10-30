package com.taxonomy.portfolio.service;

import com.taxonomy.analysis.service.*;
import com.taxonomy.extension.api.llm.ProviderId;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequirementIntakeMatcherTest {
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

    private void ready(String reply) {
        var provider = new ProviderId("GEMINI");
        when(config.getActiveProviderId()).thenReturn(provider);
        when(config.isProviderConfigured(provider)).thenReturn(true);
        when(config.getApiKey(provider)).thenReturn("fixture");
        when(gateways.getGatewayById(provider)).thenReturn(gateway);
        when(gateway.sendHttpRequest(anyString(), eq("fixture"), any(LlmCompletionOptions.class))).thenReturn("envelope");
        when(gateway.extractResponseText("envelope")).thenReturn(reply);
    }

    static Candidate candidate() {
        return new Candidate("r1", "REQUIREMENT", "repo", "work", "draft", 2L, "P", 7L, 9L,
                "REQ", "Response time", "Respond within five seconds", "saved-hash", null, null,
                List.of(), List.of("NEW", "CHANGE", "LINK", "VIEW"));
    }
}
