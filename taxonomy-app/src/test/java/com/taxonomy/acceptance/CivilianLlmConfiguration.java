package com.taxonomy.acceptance;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Replaces only the remote HTTP exchange, including in asynchronous Copilot jobs. */
@TestConfiguration(proxyBeanMethods = false)
public class CivilianLlmConfiguration {
    public static final String URL = "https://civilian.invalid/v1/chat/completions";

    @Bean @Lazy(false)
    ScenarioLlmPlayback civilianLlmPlayback(RestTemplate restTemplate) throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        var mapper = new ObjectMapper();
        var server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(ExpectedCount.manyTimes(), request -> { }).andRespond(request -> {
            if (!request.getURI().toString().equals(URL) || request.getMethod() != HttpMethod.POST) {
                playback.reject("Unexpected remote request; no network fallback permitted");
            }
            var body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
            if (!body.path("model").asText().equals("civilian-fixture")
                    || body.path("messages").size() != 1
                    || !body.at("/messages/0/role").asText().equals("user")) {
                playback.reject("Unexpected OpenAI-compatible request envelope");
            }
            return withSuccess(playback.respond(body.at("/messages/0/content").asText()),
                    MediaType.APPLICATION_JSON).createResponse(request);
        });
        return playback;
    }
}
