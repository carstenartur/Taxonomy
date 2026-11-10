package com.taxonomy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The native dialog fixture must serialize the same frozen comparison contract as the server. */
class ArchitectureReconciliationBrowserFixtureTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void unresolvedPreviewPreservesTheCapturedRecoveryContextAndHasNoInventedProposedScene() {
        var result = preview(false);
        assertThat(result.path("context")).isEqualTo(context());
        assertThat(result.path("fingerprint").asText()).isEqualTo("unresolved");
        assertThat(result.path("canAccept").asBoolean()).isFalse();
        assertThat(result.path("mergedDsl").isNull()).isTrue();
        var scenes = result.path("comparison").path("scenes");
        assertThat(scenes.size()).isEqualTo(3);
        assertThat(scenes.has("proposed")).isFalse();
        assertThat(scenes.path("base").path("nodes").get(0).path("label").asText()).isEqualTo("Original title");
        assertThat(scenes.path("local").path("nodes").get(0).path("label").asText()).isEqualTo("Local title");
        assertThat(scenes.path("incoming").path("nodes").get(0).path("label").asText()).isEqualTo("Incoming title");
    }

    @Test void reviewedPreviewSerializesTheActualProjectionWithStableIdentityAndExactAcceptanceFingerprint() {
        var result = preview(true);
        var comparison = result.path("comparison");
        assertThat(comparison.isObject()).isTrue();
        assertThat(result.path("context")).isEqualTo(context());
        assertThat(result.path("fingerprint").asText()).isEqualTo("reviewed-local");
        assertThat(result.path("canAccept").asBoolean()).isTrue();
        assertThat(result.path("conflicts").get(0).path("resolution").asText()).isEqualTo("LOCAL");
        assertThat(result.path("mergedDsl")).isEqualTo(result.path("localDsl"));
        var scenes = comparison.path("scenes");
        assertThat(scenes.size()).isEqualTo(4);
        var base = scenes.path("base").path("nodes").get(0);
        var proposed = scenes.path("proposed").path("nodes").get(0);
        assertThat(proposed.path("id").asText()).isEqualTo("fixture");
        assertThat(proposed.path("label").asText()).isEqualTo("Local title");
        assertThat(proposed.path("x")).isEqualTo(base.path("x"));
        assertThat(proposed.path("y")).isEqualTo(base.path("y"));
        assertThat(comparison.path("typeRoots").path("CoreService").asText()).isEqualTo("CR");
        assertThat(comparison.path("properties").toString()).contains("element:fixture", "title", "SEMANTIC");
    }

    private static JsonNode preview(boolean resolved) {
        var body = JSON.writeValueAsString(Map.of("context", context(), "resolutions",
                resolved ? Map.of("element:fixture", "LOCAL") : Map.of()));
        var request = new PortfolioClientTestPage.Request("POST", "/api/architecture/editor/versions/reconciliation/preview",
                "", body, Map.of());
        // Round-trip the actual HTTP body so field-name / DTO drift fails without a native browser.
        return JSON.readTree(JSON.writeValueAsString(ArchitectureStandSelectionBrowserIT.reconciliationPreview(request)));
    }

    private static JsonNode context() {
        return JSON.valueToTree(Map.of("repositoryId", "recovery-repository", "workspaceScopeKey", "recovery-workspace",
                "branch", "recovery-branch", "commit", "c".repeat(40), "revision", 3,
                "actor", "recovery-author", "writeMode", "PRIVATE_WORKSPACE"));
    }
}
