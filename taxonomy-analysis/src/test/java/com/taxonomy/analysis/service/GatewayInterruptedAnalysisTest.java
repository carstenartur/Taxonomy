package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayInterruptedAnalysisTest {
    @Test void interruptedOpenAiCallDoesNotSendOrRetryHttp() {
        RestTemplate http=mock(RestTemplate.class);
        var gateway=new OpenAiCompatibleGateway(LlmProvider.OPENAI,"https://example.invalid","model",5,
                http,new ObjectMapper(),mock(LlmResponseParser.class),null,null,null);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> gateway.sendHttpRequest("requirement","unused"))
                    .isInstanceOf(RuntimeException.class).hasMessageContaining("CANCELLED");
            verifyNoInteractions(http);
        } finally { Thread.interrupted(); }
    }
    @Test void interruptedGeminiCallDoesNotSendOrRetryHttp() {
        RestTemplate http=mock(RestTemplate.class);
        var gateway=new GeminiGateway(mock(LlmProviderConfig.class),http,new ObjectMapper(),
                mock(LlmResponseParser.class),null,null,null);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> gateway.sendHttpRequest("requirement","unused"))
                    .isInstanceOf(RuntimeException.class).hasMessageContaining("CANCELLED");
            verifyNoInteractions(http);
        } finally { Thread.interrupted(); }
    }
}
