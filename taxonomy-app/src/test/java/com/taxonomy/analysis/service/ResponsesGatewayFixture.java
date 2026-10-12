package com.taxonomy.analysis.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;

/** Test-only routing to a real loopback server; actual contract/admission/transport are unchanged. */
public final class ResponsesGatewayFixture {
    private ResponsesGatewayFixture() { }
    public static LlmGateway gateway(String origin, ProviderRateQuotas quotas, ObjectMapper json) {
        var transport = new OpenAiResponsesHttpTransport() {
            @Override ResponseEntity<String> exchange(String endpoint, HttpHeaders headers, String body, int limit, long timeout) {
                if (!endpoint.equals(OpenAiResponsesTokenContract.COUNT_URL)
                        && !endpoint.equals(OpenAiResponsesTokenContract.GENERATION_URL)) throw new AssertionError("Unexpected production endpoint");
                return super.exchange(origin + URI.create(endpoint).getPath(), headers, body, limit, timeout);
            }
        };
        AnalysisRuntimeSettings settings = (key, fallback) -> key.startsWith("llm.rpm") || key.equals("llm.retry.max") ? 0 : fallback;
        var gateway = new OpenAiCompatibleGateway(LlmProvider.OPENAI, LlmProviderConfig.OPENAI_URL, LlmProviderConfig.OPENAI_MODEL,
                0, new RestTemplate(), json, new LlmResponseParser(json), settings, null, null, transport);
        gateway.configureProviderQuotas(quotas);
        return gateway;
    }
}
