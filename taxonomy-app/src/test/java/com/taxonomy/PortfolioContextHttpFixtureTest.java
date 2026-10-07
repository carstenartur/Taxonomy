package com.taxonomy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.taxonomy.PortfolioContextHttpFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The Selenium fixture must enforce its HTTP boundary before a browser uses it. */
class PortfolioContextHttpFixtureTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"", "/taxonomy", "/review/taxonomy"})
    void actualDtosAndWritesStayInsideTheExactContext(String context) throws Exception {
        try (var upstream = new Upstream(); var fixture = new PortfolioContextHttpFixture(upstream.origin());
             var client = HttpClient.newHttpClient()) {
            fixture.activate(context);
            String root = "http://127.0.0.1:" + fixture.port();
            assertThat(json.readTree(get(client, root + context + "/api/projects").body()).isEmpty()).isTrue();
            fixture.projectsAvailable = true;
            var projects = json.readTree(get(client, root + context + "/api/projects").body());
            assertThat(projects.get(0).get("id").asLong()).isEqualTo(PROJECT_A);
            assertThat(projects.get(1).get("requirementCount").asInt()).isEqualTo(1);
            var state = json.readTree(get(client, root + context + "/api/git/state").body());
            assertThat(json.convertValue(state.get("branches"), String[].class)).containsExactlyElementsOf(BRANCHES);
            var portfolio = json.readTree(get(client, root + context + "/api/projects/" + PROJECT_A + "/portfolio").body());
            var values = portfolio.get("requirementSolutionMatrix").get("values").get("QA-SOL-A");
            assertThat(values.get("QA-REQ-0").asInt()).isZero();
            assertThat(values.has("QA-REQ-EMPTY")).isFalse();
            assertThat(values.has("QA-REQ-UNKNOWN")).isFalse();
            fixture.unknownCoverage = true;
            var degraded = json.readTree(get(client, root + context + "/api/projects/" + PROJECT_A + "/portfolio").body());
            assertThat(degraded.get("requirementSolutionMatrix").get("values").get("QA-SOL-A").get("QA-REQ-UNKNOWN").isNull()).isTrue();

            String version = context + "/api/projects/" + PROJECT_A + "/requirements/" + REQUIREMENT_A + "/versions";
            var request = HttpRequest.newBuilder(URI.create(root + version)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"  Draft\\nStill here  \",\"changeReason\":\"Checked\",\"source\":null}")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json.readTree(response.body()).get("detail").asText()).isEqualTo(CONFLICT);
            assertThat(fixture.submittedVersions.getFirst().text()).isEqualTo("  Draft\nStill here  ");
            String sibling = context.isEmpty() ? "/sibling" : context + "-sibling";
            assertThat(get(client, root + sibling + "/api/projects").statusCode()).isEqualTo(599);
            assertThat(get(client, root + context + "/api/projects/" + PROJECT_A + "/unexpected").statusCode()).isEqualTo(599);
            var blocked = client.send(HttpRequest.newBuilder(URI.create(root + context + "/api/projects"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(blocked.statusCode()).isEqualTo(599);
            assertThat(upstream.requests).isEmpty();
        }
    }

    @Test
    void aNetworkPathCannotMoveTheProxyToAnotherOrigin() throws Exception {
        try (var upstream = new Upstream(); var fixture = new PortfolioContextHttpFixture(upstream.origin())) {
            URI target = fixture.forwardingTarget(URI.create("//unrelated.invalid/must-not-switch-origin"));
            assertThat(target.getAuthority()).isEqualTo(upstream.origin().getAuthority());
            assertThat(target.getRawPath()).isEqualTo("//unrelated.invalid/must-not-switch-origin");
            assertThat(upstream.requests).isEmpty();
        }
    }

    @Test
    void inactiveFixtureForwardsTheRealBackendRequestWithoutReplacingItsBody() throws Exception {
        try (var upstream = new Upstream(); var fixture = new PortfolioContextHttpFixture(upstream.origin());
             var client = HttpClient.newHttpClient()) {
            String root = "http://127.0.0.1:" + fixture.port();
            var request = HttpRequest.newBuilder(URI.create(root + "/api/projects"))
                    .POST(HttpRequest.BodyPublishers.ofString("actual backend form")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("actual backend form");
            assertThat(upstream.requests).containsExactly("/api/projects");
            assertThat(fixture.submittedVersions).isEmpty();
            var redirect = get(client, root + "/session-redirect");
            assertThat(redirect.statusCode()).isEqualTo(302);
            assertThat(redirect.headers().firstValue("Location")).contains("/taxonomy/login?continue");
            assertThat(redirect.headers().firstValue("Set-Cookie")).contains("JSESSIONID=qa-cookie; Path=/taxonomy; HttpOnly");
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final class Upstream implements AutoCloseable {
        final HttpServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();
        Upstream() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.add(exchange.getRequestURI().toString());
                if (exchange.getRequestURI().getPath().equals("/session-redirect")) {
                    exchange.getResponseHeaders().set("Location", origin() + "/taxonomy/login?continue");
                    exchange.getResponseHeaders().set("Set-Cookie", "JSESSIONID=qa-cookie; Path=/taxonomy; HttpOnly");
                    exchange.sendResponseHeaders(302, -1); exchange.close(); return;
                }
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (body.length == 0) body = "Real backend response".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            server.start();
        }
        int port() { return server.getAddress().getPort(); }
        URI origin() { return URI.create("http://127.0.0.1:" + port()); }
        @Override public void close() { server.stop(0); }
    }
}
