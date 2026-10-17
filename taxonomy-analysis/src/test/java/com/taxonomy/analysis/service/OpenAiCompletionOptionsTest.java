package com.taxonomy.analysis.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class OpenAiCompletionOptionsTest {
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private HttpServer server;
    private OpenAiCompatibleGateway gateway;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] answer = (status == 200
                    ? "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\"}}]}"
                    : "{\"error\":{\"message\":\"sensitive-provider-body\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        gateway = new OpenAiCompatibleGateway(LlmProvider.CUSTOM_OPENAI,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions", "fixture-model",
                0, new RestTemplate(), json, new LlmResponseParser(json), (key, fallback) -> 0, null, null);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void optedCompletionSendsStrictSchemaOutputLimitAndDeterministicTemperature() {
        gateway.sendHttpRequest("prompt", "", options(1024));

        JsonNode body = requests.getFirst();
        assertThat(body.has("max_tokens")).isTrue();
        assertThat(body.path("max_tokens").intValue()).isEqualTo(1024);
        assertThat(body.path("temperature").doubleValue()).isZero();
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_schema");
        JsonNode schema = body.path("response_format").path("json_schema");
        assertThat(schema.path("name").asText()).isEqualTo("relation_decisions");
        assertThat(schema.path("strict").booleanValue()).isTrue();
        assertThat(schema.path("schema")).isEqualTo(json.valueToTree(options(1024).schema()));
    }

    @Test void ordinaryCallsPreserveTheirExistingWireShape() {
        gateway.sendHttpRequest("prompt", "");

        assertThat(requests.getFirst().size()).isEqualTo(2);
        assertThat(requests.getFirst().has("max_tokens")).isFalse();
        assertThat(requests.getFirst().has("temperature")).isFalse();
        assertThat(requests.getFirst().has("response_format")).isFalse();
    }

    @Test void customSchemaOptOutRetainsOutputLimitAndTemperature() {
        gateway.configureCompletionLimits(false, 4096);
        gateway.sendHttpRequest("prompt", "", options(1024));

        assertThat(requests.getFirst().has("max_tokens")).isTrue();
        assertThat(requests.getFirst().has("response_format")).isFalse();
        assertThat(requests.getFirst().path("max_tokens").intValue()).isEqualTo(1024);
        assertThat(requests.getFirst().path("temperature").doubleValue()).isZero();
    }

    @Test void configuredCustomOutputAllowanceCapsOptedRequests() {
        gateway.configureCompletionLimits(true, 128);
        gateway.sendHttpRequest("prompt", "", options(1024));

        assertThat(requests.getFirst().path("max_tokens").intValue()).isEqualTo(128);
    }

    @Test void unrelatedBadRequestsAndAuthenticationFailuresAreNeverRetriedAsCapabilityFallbacks() {
        for (int rejected : List.of(400, 401, 403)) {
            requests.clear();
            status = rejected;
            assertThatThrownBy(() -> gateway.sendHttpRequest("prompt", "", options(1024)))
                    .isInstanceOf(LlmProviderException.class)
                    .hasMessageNotContaining("sensitive-provider-body");
            assertThat(requests).hasSize(1);
        }
    }

    static LlmCompletionOptions options(int tokens) {
        return new LlmCompletionOptions("relation_decisions", Map.of("type", "object", "properties", Map.of(),
                "additionalProperties", false, "required", List.of()), tokens);
    }
}
