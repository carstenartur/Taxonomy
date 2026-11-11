package com.taxonomy;

import com.taxonomy.testsupport.BrowserSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static com.taxonomy.PortfolioClientTestPage.json;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The native browser's authored response must contain the actual frozen comparison contract. */
class ReconciliationFixtureContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void requirementRecoverySetupKeepsItsPreviewRouteAfterReset(@TempDir Path downloads) throws Exception {
        var driver = mock(RemoteWebDriver.class, RETURNS_DEEP_STUBS);
        var browser = mock(BrowserSession.class);
        when(browser.driver()).thenReturn(driver);
        // Only native browser startup is replaced; the real constructor, route reset and HTTP server run.
        try (var browserFactory = mockStatic(BrowserSession.class)) {
            browserFactory.when(() -> BrowserSession.open(anyInt(), eq(downloads))).thenAnswer(call -> {
                when(browser.origin()).thenReturn("http://127.0.0.1:" + call.<Integer>getArgument(0));
                return browser;
            });
            try (var page = new PortfolioClientTestPage(downloads); var client = HttpClient.newHttpClient()) {
                String path = "/api/architecture/editor/versions/reconciliation/preview";
                var captured = Map.of("repositoryId", "control-architecture", "workspaceScopeKey", "qa-copy",
                        "branch", "draft", "commit", "c".repeat(40), "revision", 3,
                        "actor", "qa-client", "writeMode", "PRIVATE_WORKSPACE");
                var request = HttpRequest.newBuilder(URI.create(page.origin() + path))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                                Map.of("context", captured, "resolutions", Map.of())))).build();
                // The native @BeforeEach registers this response before the requirement fixture resets routes.
                page.route("POST", path, incoming -> json(200, ArchitectureStandSelectionBrowserIT.reconciliationPreview(incoming)));
                assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());

                ArchitectureStandSelectionBrowserIT.requirementReconciliationFixture(page);

                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(),
                        "Requirement recovery must retain the real preview endpoint after its route reset: " + response.body());
                var preview = JSON.readTree(response.body());
                assertEquals(JSON.valueToTree(captured), preview.path("context"));
                assertEquals("unresolved", preview.path("fingerprint").asText());
                assertEquals(1, preview.path("conflicts").size());
                assertEquals("element:fixture", preview.path("conflicts").path(0).path("id").asText());
                assertEquals(3, preview.path("comparison").path("scenes").size());
                assertFalse(preview.path("canAccept").asBoolean());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void browserPreviewRetainsItsModelsAndFrozenComparison(boolean resolved) {
        var captured = Map.of("repositoryId", "recovery-repository", "workspaceScopeKey", "recovery-workspace",
                "branch", "recovery-branch", "commit", "c".repeat(40), "revision", 3,
                "actor", "recovery-author", "writeMode", "PRIVATE_WORKSPACE");
        var request = new PortfolioClientTestPage.Request("POST",
                "/api/architecture/editor/versions/reconciliation/preview", null,
                JSON.writeValueAsString(Map.of("context", captured,
                        "resolutions", resolved ? Map.of("element:fixture", "LOCAL") : Map.of())),
                Map.of());
        // Round-trip the native producer's actual HTTP body so DTO and field-name drift fail here.
        var value = JSON.readTree(JSON.writeValueAsString(ArchitectureStandSelectionBrowserIT.reconciliationPreview(request)));

        assertEquals(JSON.valueToTree(captured), value.path("context"));
        assertEquals("b".repeat(40), value.path("headCommit").asText());
        assertEquals(resolved ? "reviewed-local" : "unresolved", value.path("fingerprint").asText());
        assertEquals(resolved, value.path("canAccept").asBoolean());
        assertTrue(value.path("comparison").isObject(), "The real renderer requires the frozen comparison");
        var scenes = value.path("comparison").path("scenes");
        assertEquals(resolved ? 4 : 3, scenes.size());
        for (var part : Map.of("base", "Original title", "local", "Local title", "incoming", "Incoming title").entrySet()) {
            var nodes = scenes.path(part.getKey()).path("nodes");
            assertEquals(1, nodes.size(), part.getKey() + " must retain its authored element");
            assertEquals("fixture", nodes.path(0).path("id").asText());
            assertEquals(part.getValue(), nodes.path(0).path("label").asText());
        }
        assertEquals(1, value.path("conflicts").size());
        assertEquals("element:fixture", value.path("conflicts").path(0).path("id").asText());
        if (resolved) {
            var proposedNodes = scenes.path("proposed").path("nodes");
            assertEquals(1, proposedNodes.size());
            var proposed = proposedNodes.path(0);
            var base = scenes.path("base").path("nodes").path(0);
            assertEquals("fixture", proposed.path("id").asText());
            assertEquals("Local title", proposed.path("label").asText());
            assertEquals(base.path("x"), proposed.path("x"));
            assertEquals(base.path("y"), proposed.path("y"));
            assertEquals(value.path("localDsl"), value.path("mergedDsl"));
            assertEquals("LOCAL", value.path("conflicts").path(0).path("resolution").asText());
        } else {
            assertFalse(scenes.has("proposed"), "Unresolved choices must not invent an empty proposed model");
            assertTrue(value.path("mergedDsl").isNull());
            assertTrue(value.path("conflicts").path(0).path("resolution").isNull());
        }
        assertEquals("CR", value.path("comparison").path("typeRoots").path("CoreService").asText());
        var differences = value.path("comparison").path("properties");
        assertEquals(1, differences.size());
        assertEquals("element:fixture", differences.path(0).path("blockId").asText());
        assertEquals("title", differences.path(0).path("property").asText());
        assertEquals("SEMANTIC", differences.path(0).path("category").asText());
        assertEquals("Original title", differences.path(0).path("base").path(0).asText());
        assertEquals("Local title", differences.path(0).path("local").path(0).asText());
        assertEquals("Incoming title", differences.path(0).path("incoming").path(0).asText());
        if (resolved) assertEquals("Local title", differences.path(0).path("proposed").path(0).asText());
        else assertTrue(differences.path(0).path("proposed").isNull());
    }
}
