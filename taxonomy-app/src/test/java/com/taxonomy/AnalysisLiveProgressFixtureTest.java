package com.taxonomy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Fast HTTP regression for the same handler used by the real-browser progress tests. */
class AnalysisLiveProgressFixtureTest {
    private static HttpServer server;
    private static HttpClient client;
    private static String statusUrl;

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/analysis-runs", AnalysisLiveProgressUiIT::serve);
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        statusUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/analysis-runs/" + AnalysisLiveProgressUiIT.ID;
    }

    @AfterAll static void stop() {
        try { if (client != null) client.close(); }
        finally { if (server != null) server.stop(0); }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    void acceptsAdmissionFlagWithoutWeakeningWorkspacePinning(
            String description, String query, String workspaceHeader, int expectedStatus) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(statusUrl + (query == null ? "" : "?" + query)))
                .timeout(Duration.ofSeconds(5));
        if (workspaceHeader != null) request.header("X-Taxonomy-Workspace-Id", workspaceHeader);
        var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(description).isEqualTo(expectedStatus);
        if (expectedStatus == 200) {
            assertThat(response.body()).contains("\"operationId\":\"" + AnalysisLiveProgressUiIT.ID + "\"");
        } else {
            assertThat(response.body()).isEmpty();
        }
    }

    static Stream<Arguments> requests() {
        return Stream.of(
                Arguments.of("ordinary poll", "workspaceId=workspace-a", "workspace-a", 200),
                Arguments.of("initial poll", "workspaceId=workspace-a&waitForRegistration=true", "workspace-a", 200),
                Arguments.of("reordered initial poll", "waitForRegistration=true&workspaceId=workspace-a", "workspace-a", 200),
                Arguments.of("explicit strict poll", "workspaceId=workspace-a&waitForRegistration=false", "workspace-a", 200),
                Arguments.of("missing query", null, "workspace-a", 403),
                Arguments.of("missing workspace parameter", "waitForRegistration=true", "workspace-a", 403),
                Arguments.of("different workspace parameter", "workspaceId=workspace-b&waitForRegistration=true", "workspace-a", 403),
                Arguments.of("different workspace header", "workspaceId=workspace-a&waitForRegistration=true", "workspace-b", 403),
                Arguments.of("missing workspace header", "workspaceId=workspace-a&waitForRegistration=true", null, 403),
                Arguments.of("duplicate matching parameter", "workspaceId=workspace-a&workspaceId=workspace-a", "workspace-a", 403),
                Arguments.of("conflicting parameters", "workspaceId=workspace-a&workspaceId=workspace-b", "workspace-a", 403),
                Arguments.of("misleading parameter name", "otherworkspaceId=workspace-a&waitForRegistration=true", "workspace-a", 403));
    }
}
