package com.taxonomy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the workspace transport contract for reverse-proxy servlet prefixes. */
class AnalysisSessionContextPathRoutingContractTest {

    @Test
    void loadsTheFetchAdapterBetweenCoreAndCanonicalApiRouting()
            throws IOException {
        String loader = Files.readString(findRepositoryFile(
                "taxonomy-app/src/main/resources/static/js/core/"
                        + "taxonomy-analysis-session.js"));

        int core = loader.indexOf("taxonomy-analysis-session-core.js");
        int adapter = loader.indexOf(
                "taxonomy-analysis-session-context-path-routing.js");
        int apiRouting = loader.indexOf("taxonomy-analysis-session-api-routing.js");
        int projects = loader.indexOf("taxonomy-analysis-session-projects.js");

        assertThat(core).isGreaterThanOrEqualTo(0);
        assertThat(adapter)
                .as("the fetch adapter requires the shared runtime exported by the core")
                .isGreaterThan(core);
        assertThat(apiRouting)
                .as("the canonical API adapter remains the single SSE routing owner")
                .isGreaterThan(adapter);
        assertThat(projects)
                .as("workspace startup must capture the final transport installers")
                .isGreaterThan(apiRouting);
    }

    @Test
    void decoratesOnlyAlreadyPrefixedFetchCallsAndLeavesSseToApiRouting()
            throws IOException {
        String adapter = Files.readString(findRepositoryFile(
                "taxonomy-app/src/main/resources/static/js/core/"
                        + "taxonomy-analysis-session-context-path-routing.js"));

        assertThat(adapter)
                .contains("window.TaxonomyI18n.resolveUrl('/api/')")
                .contains("url.origin !== window.location.origin")
                .contains("headers.set(WORKSPACE_HEADER, runtime.workspaceId)")
                .contains("installRootFetchRouting()")
                .contains("if (prefix === '/api/') return false")
                .contains("ROOT_MARKER = '__taxonomyWorkspaceRouting'")
                .contains("CONTEXT_PATH_MARKER = '__taxonomyContextPathWorkspaceRouting'")
                .contains("markRoutingInstalled(routedFetch)")
                .contains("SSE remains owned by taxonomy-analysis-session-api-routing.js")
                .doesNotContain("installRootEventSourceRouting")
                .doesNotContain("RoutedEventSource");
    }

    private static Path findRepositoryFile(String relativePath) {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Repository file not found from the current working directory: "
                        + relativePath);
    }
}
