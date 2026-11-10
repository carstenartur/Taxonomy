package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityPerformanceScopeRepositoryTest {
    private static final String BENCHMARK = "Measure OpenTelemetry performance budget";
    private static final String STATIC_EXCLUSION = ":(exclude)taxonomy-app/src/main/resources/static/**";
    private static final String TEMPLATE_EXCLUSION = ":(exclude)taxonomy-app/src/main/resources/templates/**";

    @Test
    void checkedOutWorkflowKeepsPerformanceVerificationScopedAndIndependent() throws IOException {
        assertThat(failures(workflow())).as("Performance scope contract failed").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("workflowRegressions")
    void invalidPerformanceScopeFailsClosed(
            String scenario, String original, String replacement, String diagnostic) throws IOException {
        String workflow = workflow();
        assertThat(workflow).as(scenario).contains(original);
        assertThat(failures(workflow.replace(original, replacement)))
                .anyMatch(failure -> failure.contains(diagnostic));
    }

    private static Stream<Arguments> workflowRegressions() {
        return Stream.of(
                Arguments.of("compare the synthetic merge commit",
                        "${PR_BASE_SHA}...${PR_HEAD_SHA}", "${PR_BASE_SHA}...HEAD",
                        "Scope detection must compare base.sha with head.sha"),
                Arguments.of("take the head from the workflow instead of the PR event",
                        "PR_HEAD_SHA: ${{ github.event.pull_request.head.sha }}", "PR_HEAD_SHA: ${{ github.sha }}",
                        "PR_HEAD_SHA: ${{ github.event.pull_request.head.sha }}"),
                Arguments.of("skip the benchmark on non-PR runs",
                        "if: github.event_name != 'pull_request' || steps.observability-performance-scope.outputs.run == 'true'",
                        "if: steps.observability-performance-scope.outputs.run == 'true'",
                        "Main, tag, release and manual CI runs"),
                Arguments.of("skip benchmark enforcement",
                        "TAXONOMY_OBSERVABILITY_PERFORMANCE_ENFORCE: 'true'",
                        "TAXONOMY_OBSERVABILITY_PERFORMANCE_ENFORCE: 'false'",
                        "TAXONOMY_OBSERVABILITY_PERFORMANCE_ENFORCE: 'true'"),
                Arguments.of("drop the static UI exclusion",
                        " ':(exclude)taxonomy-app/src/main/resources/static/**'", "",
                        "Pure static/template UI changes"),
                Arguments.of("drop the template UI exclusion",
                        " ':(exclude)taxonomy-app/src/main/resources/templates/**'", "",
                        "Pure static/template UI changes"),
                Arguments.of("limit the runtime scope to Java source",
                        " taxonomy-app/src/main ", " taxonomy-app/src/main/java ",
                        "runtime configuration and resources must stay covered"),
                Arguments.of("drop the runtime scope but leave UI exclusions",
                        " taxonomy-app/src/main ", " ",
                        "Application runtime changes must trigger the performance budget"),
                Arguments.of("run the benchmark for unrelated workflow edits",
                        " -- pom.xml Dockerfile ", " -- pom.xml Dockerfile .github/workflows/ci-cd.yml ",
                        "Editing unrelated CI steps"),
                Arguments.of("move the benchmark onto the core critical path",
                        "      - name: Verify release and delivery contracts",
                        "      - name: Measure OpenTelemetry performance budget",
                        "must not extend the core reactor critical path"),
                Arguments.of("lose the step recording the performance decision",
                        "      - name: Record observability lane scope", "      - name: Record unrelated evidence",
                        "Could not locate the ordered performance steps"));
    }

    private static String workflow() throws IOException {
        return Files.readString(ReleaseOrchestrationChecks.repositoryRoot().resolve(".github/workflows/ci-cd.yml"));
    }

    private static List<String> failures(String source) {
        String workflow = source.replace("\r\n", "\n").replace('\r', '\n');
        int coreStart = workflow.indexOf("\n  core:\n");
        int observabilityStart = workflow.indexOf("\n  observability:\n");
        int uiPlanStart = workflow.indexOf("\n  ui-plan:\n");
        if (coreStart < 0 || observabilityStart <= coreStart || uiPlanStart <= observabilityStart) {
            return List.of("Could not locate parallel performance lane in ci-cd.yml");
        }

        List<String> failures = new ArrayList<>();
        String core = workflow.substring(coreStart, observabilityStart);
        String observability = workflow.substring(observabilityStart, uiPlanStart);
        if (core.contains(BENCHMARK)) {
            failures.add("The expensive performance budget must not extend the core reactor critical path");
        }
        require(observability, failures, "observability:\n    name: OpenTelemetry performance budget");
        int scopeStart = observability.indexOf("\n      - name: Detect performance-sensitive changes\n");
        int measurementStart = observability.indexOf("\n      - name: " + BENCHMARK + "\n");
        int recordingStart = observability.indexOf("\n      - name: Record observability lane scope\n");
        if (scopeStart < 0 || measurementStart <= scopeStart || recordingStart <= measurementStart) {
            failures.add("Could not locate the ordered performance steps in the parallel observability job");
            return failures;
        }

        String scope = stepBlock(observability, scopeStart);
        require(scope, failures,
                "id: observability-performance-scope",
                "PR_BASE_SHA: ${{ github.event.pull_request.base.sha }}",
                "PR_HEAD_SHA: ${{ github.event.pull_request.head.sha }}",
                " pom.xml ", " Dockerfile ", " taxonomy-app/pom.xml ");
        if (!scope.contains("git diff --quiet \"${PR_BASE_SHA}...${PR_HEAD_SHA}\" --") || scope.contains("...HEAD")) {
            failures.add("Scope detection must compare base.sha with head.sha, not the synthetic PR merge HEAD");
        }
        if (scope.contains(".github/workflows/ci-cd.yml")) {
            failures.add("Editing unrelated CI steps must not by itself force the expensive benchmark");
        }
        if (!scope.contains(" taxonomy-app/src/main ")) {
            failures.add("Application runtime changes must trigger the performance budget on pull requests");
        }
        if (!scope.contains(STATIC_EXCLUSION) || !scope.contains(TEMPLATE_EXCLUSION)) {
            failures.add("Pure static/template UI changes must not force the server OpenTelemetry benchmark");
        }
        if (scope.contains("taxonomy-app/src/main/java")) {
            failures.add("Do not replace the inclusive src/main path with Java-only matching; "
                    + "runtime configuration and resources must stay covered");
        }

        String measurement = stepBlock(observability, measurementStart);
        String condition = "        if: github.event_name != 'pull_request' || "
                + "steps.observability-performance-scope.outputs.run == 'true'";
        if (measurement.lines().noneMatch(condition::equals)) {
            failures.add("Main, tag, release and manual CI runs must not be allowed to skip the performance budget; "
                    + "pull requests must use the recorded scope decision");
        }
        require(measurement, failures,
                "TAXONOMY_OBSERVABILITY_PERFORMANCE_ENFORCE: 'true'",
                "run: bash .github/scripts/run-observability-performance.sh");
        return failures;
    }

    private static String stepBlock(String job, int start) {
        int end = job.indexOf("\n      - ", start + 1);
        return job.substring(start, end < 0 ? job.length() : end);
    }

    private static void require(String block, List<String> failures, String... needles) {
        for (String needle : needles) {
            if (!block.contains(needle)) {
                failures.add("ci-cd.yml is missing " + needle);
            }
        }
    }
}
