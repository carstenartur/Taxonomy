package com.taxonomy.security.keycloak;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakHealthIndicatorTest {

    @Test
    void reportsUpWhenJwksEndpointIsReachable() throws Exception {
        try (TestHttpEndpoint endpoint = TestHttpEndpoint.responding(200, "{\"keys\":[]}")) {
            Health health = healthFor(endpoint.url());

            assertThat(health.getStatus().getCode()).isEqualTo("UP");
            assertThat(health.getDetails()).containsEntry("jwksEndpoint", endpoint.url());
        }
    }

    @Test
    void reportsDownWhenJwksEndpointReturnsAnError() throws Exception {
        try (TestHttpEndpoint endpoint = TestHttpEndpoint.responding(503, "unavailable")) {
            Health health = healthFor(endpoint.url());

            assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
            assertThat(health.getDetails())
                    .containsEntry("jwksEndpoint", endpoint.url())
                    .containsEntry("statusCode", 503);
        }
    }

    @Test
    void reportsDownForInvalidJwksConfiguration() {
        Health health = healthFor("not a valid URI");

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("jwksEndpoint", "not a valid URI");
        assertThat(health.getDetails().get("error").toString())
                .startsWith("IllegalArgumentException:");
    }

    @Test
    void reportsDownWhenIdentityProviderIsUnavailable() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        String url = "http://127.0.0.1:" + unusedPort + "/certs";

        Health health = healthFor(url);

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("jwksEndpoint", url);
        assertThat(health.getDetails().get("error").toString())
                .startsWith("ConnectException");
    }

    private static Health healthFor(String jwksEndpoint) {
        KeycloakHealthIndicator indicator = new KeycloakHealthIndicator();
        ReflectionTestUtils.setField(indicator, "jwkSetUri", jwksEndpoint);
        return indicator.health();
    }

    private static final class TestHttpEndpoint implements AutoCloseable {
        private final HttpServer server;

        private TestHttpEndpoint(HttpServer server) {
            this.server = server;
        }

        static TestHttpEndpoint responding(int status, String body) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            server.createContext("/certs", exchange -> {
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return new TestHttpEndpoint(server);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/certs";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
