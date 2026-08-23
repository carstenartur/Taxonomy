package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityPerformanceScopeRepositoryTest {

    private static final String STATIC_EXCLUSION =
            ":(exclude)taxonomy-app/src/main/resources/static/**";
    private static final String TEMPLATE_EXCLUSION =
            ":(exclude)taxonomy-app/src/main/resources/templates/**";

    @Test
    void performanceGateUsesTheReviewedRuntimeScopeWithoutPython()
            throws Exception {
        Path root = findRepositoryRoot();
        Path workflowPath = root.resolve(".github/workflows/ci-cd.yml");
        Path removedChecker = root.resolve(
                ".github/scripts/check-observability-performance-scope.py");
        String workflow = Files.readString(
                workflowPath, StandardCharsets.UTF_8);

        assertThat(workflow).contains(
                "- name: Detect performance-sensitive changes",
                "PR_BASE_SHA: ${{ github.event.pull_request.base.sha }}",
                "PR_HEAD_SHA: ${{ github.event.pull_request.head.sha }}",
                "git diff --quiet \"${PR_BASE_SHA}...${PR_HEAD_SHA}\" --",
                "pom.xml",
                "Dockerfile",
                "taxonomy-app/pom.xml",
                "taxonomy-app/src/main",
                STATIC_EXCLUSION,
                TEMPLATE_EXCLUSION,
                ".github/scripts/run-observability-performance.sh",
                "github.event_name != 'pull_request' || "
                        + "steps.observability-performance-scope.outputs.run == 'true'",
                "TAXONOMY_OBSERVABILITY_PERFORMANCE_ENFORCE: 'true'",
                "run: bash .github/scripts/run-observability-performance.sh");

        int scopeStart = workflow.indexOf(
                "- name: Detect performance-sensitive changes");
        int performanceStart = workflow.indexOf(
                "- name: Measure OpenTelemetry performance budget");
        int performanceEnd = workflow.indexOf(
                "- name: Restore pinned embedding model");
        assertThat(scopeStart).isGreaterThanOrEqualTo(0);
        assertThat(performanceStart).isGreaterThan(scopeStart);
        assertThat(performanceEnd).isGreaterThan(performanceStart);

        String scopeBlock = workflow.substring(scopeStart, performanceStart);
        String performanceBlock = workflow.substring(
                performanceStart, performanceEnd);
        assertThat(scopeBlock)
                .doesNotContain("...HEAD")
                .doesNotContain(".github/workflows/ci-cd.yml")
                .doesNotContain("taxonomy-app/src/main/java")
                .contains("taxonomy-app/src/main")
                .contains(STATIC_EXCLUSION, TEMPLATE_EXCLUSION);
        assertThat(performanceBlock)
                .contains("github.event_name != 'pull_request'")
                .contains("steps.observability-performance-scope.outputs.run == 'true'");

        assertThat(removedChecker).doesNotExist();
        assertThat(workflow)
                .doesNotContain("check-observability-performance-scope.py");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            "taxonomy-tooling/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Unable to locate Taxonomy repository root");
    }
}
