package com.taxonomy.build;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Prevent extracted plugin inputs from silently bypassing the existing CI lanes. */
class PluginWorkflowTriggerTest {
    private static final String TEMPLATE_API =
            "taxonomy-templates-api/src/main/java/com/taxonomy/templates/api/DocumentTemplates.java";

    @TempDir Path temporary;
    private Path root;

    @BeforeEach
    void findRepositoryRoot() {
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/workflows/ci-cd.yml"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository root").isNotNull();
    }

    static Stream<Arguments> consumerTriggers() {
        Stream<Arguments> templateApi = Stream.of(
                List.of("kubernetes-constrained-smoke.yml", "pull_request"),
                List.of("kubernetes-constrained-smoke.yml", "push"),
                List.of("document-template-report-e2e.yml", "pull_request"),
                List.of("jgit-storage-hibernate-contract.yml", "pull_request"),
                List.of("jgit-storage-hibernate-contract.yml", "push"),
                List.of("codeql.yml", "pull_request"),
                List.of("codeql.yml", "push"),
                List.of("security-scan.yml", "pull_request"))
                .flatMap(event -> Stream.of(TEMPLATE_API, "taxonomy-templates-api/pom.xml")
                        .map(path -> Arguments.of(event.get(0), event.get(1), path)));
        // The CI workflow owns the full plugin verification and build commands.
        // A workflow-only fix must still receive source analysis at its new SHA.
        return Stream.concat(templateApi, Stream.of("pull_request", "push")
                .map(event -> Arguments.of("codeql.yml", event, ".github/workflows/ci-cd.yml")));
    }

    @ParameterizedTest(name = "{0} / {1} includes {2}")
    @MethodSource("consumerTriggers")
    void consumerInputsTriggerTheirChecks(String workflow, String event, String changedPath)
            throws Exception {
        assertThat(root.resolve(changedPath)).isRegularFile();
        assertThat(includesPath(triggerPaths(workflow, event), changedPath))
                .as("%s / %s must run for an isolated change to %s", workflow, event, changedPath)
                .isTrue();
    }

    @ParameterizedTest(name = "{0} / {1} excludes only plugin-root README files")
    @CsvSource({
            "kubernetes-constrained-smoke.yml, pull_request",
            "security-scan.yml, pull_request",
            "codeql.yml, pull_request",
            "codeql.yml, push"
    })
    void pluginReadmeExclusionsPreserveNestedResourcesAndCode(String workflow, String event)
            throws Exception {
        List<String> paths = triggerPaths(workflow, event);
        assertThat(paths).containsSubsequence("plugins/**", "!plugins/*/README.md", "!plugins/*/README_DE.md");
        for (String readme : List.of("README.md", "README_DE.md")) {
            assertThat(includesPath(paths, "plugins/taxonomy-mermaid-plugin/" + readme))
                    .as("plugin-root prose: %s / %s / %s", workflow, event, readme).isFalse();
            assertThat(includesPath(paths, "plugins/taxonomy-mermaid-plugin/src/main/resources/" + readme))
                    .as("nested packaged resource: %s / %s / %s", workflow, event, readme).isTrue();
        }
        for (String input : List.of("pom.xml", "src/main/java/com/taxonomy/plugins/mermaid/MermaidPlugin.java",
                "src/main/resources/META-INF/services/com.taxonomy.extension.api.plugin.TaxonomyPlugin")) {
            assertThat(includesPath(paths, "plugins/taxonomy-mermaid-plugin/" + input))
                    .as("plugin implementation: %s / %s / %s", workflow, event, input).isTrue();
        }
    }

    @Test
    void supportedPathFiltersApplyEveryMatchInOrderAndRejectUnknownSyntax() {
        String readme = "plugins/example/README.md";
        assertThat(includesPath(List.of("plugins/**", "!plugins/*/README.md"), readme)).isFalse();
        assertThat(includesPath(List.of("!plugins/*/README.md", "plugins/**"), readme)).isTrue();
        assertThat(includesPath(List.of("plugins/**", "!plugins/*/README.md", readme), readme)).isTrue();
        assertThat(includesPath(List.of("plugins/**", "!plugins/*/README.md"), "unrelated/pom.xml")).isFalse();
        for (String unsupported : List.of("plugins/?", "plugins/+", "plugins/[ab]", "plugins/{a,b}",
                "!!plugins/**", "plugins/!README.md", "!")) {
            assertThatThrownBy(() -> includesPath(List.of("plugins/**", unsupported), readme))
                    .as("unknown filter syntax must not silently pass: %s", unsupported)
                    .isInstanceOf(AssertionError.class);
        }
    }

    private List<String> triggerPaths(String workflow, String event) throws Exception {
        Map<?, ?> document = workflow(workflow);
        // SnakeYAML's YAML 1.1 resolver also accepts the GitHub key "on" as Boolean.TRUE.
        Map<?, ?> events = map(document.containsKey("on") ? document.get("on") : document.get(true));
        Object paths = map(events.get(event)).get("paths");
        assertThat(paths).as("%s / %s path filters", workflow, event).isInstanceOf(List.class);
        return ((List<?>) paths).stream().map(pattern -> {
            assertThat(pattern).isInstanceOf(String.class);
            return (String) pattern;
        }).toList();
    }

    private static boolean includesPath(List<String> patterns, String changedPath) {
        boolean included = false;
        for (String pattern : patterns) {
            boolean negative = pattern.startsWith("!");
            String glob = negative ? pattern.substring(1) : pattern;
            // Only the checked-in literal/*/** subset, with optional leading negation.
            // The last matching filter wins; a later positive filter can re-include a path.
            assertThat(glob).isNotBlank().doesNotContain("!", "?", "+", "[", "{", "\\");
            if (FileSystems.getDefault().getPathMatcher("glob:" + glob).matches(Path.of(changedPath))) {
                included = !negative;
            }
        }
        return included;
    }

    @ParameterizedTest(name = "performance scope: {0} -> {1}")
    @CsvSource({
            "taxonomy-templates-api/pom.xml, true",
            "taxonomy-templates-api/src/main/java/com/taxonomy/templates/api/DocumentTemplates.java, true",
            "taxonomy-extension-api/pom.xml, true",
            "taxonomy-extension-api/src/main/java/com/taxonomy/extension/api/plugin/TaxonomyPlugin.java, true",
            "taxonomy-extension-runtime/pom.xml, true",
            "taxonomy-extension-runtime/src/main/java/com/taxonomy/extension/runtime/Pf4jPluginRuntime.java, true",
            "plugins/taxonomy-mermaid-plugin/pom.xml, true",
            "plugins/taxonomy-mermaid-plugin/src/main/java/com/taxonomy/plugins/mermaid/MermaidPlugin.java, true",
            "plugins/taxonomy-mermaid-plugin/src/main/resources/META-INF/services/com.taxonomy.extension.api.plugin.TaxonomyPlugin, true",
            "README.md, false",
            "plugins/taxonomy-mermaid-plugin/README.md, false",
            "plugins/taxonomy-mermaid-plugin/README_DE.md, false",
            "taxonomy-app/src/main/resources/static/css/taxonomy.css, false",
            "taxonomy-app/src/main/resources/templates/index.html, false"
    })
    void realPerformanceScopeCommandRecognizesPluginChanges(String changedPath, boolean expected)
            throws Exception {
        Map<?, ?> observability = map(map(workflow("ci-cd.yml").get("jobs")).get("observability"));
        List<?> steps = (List<?>) observability.get("steps");
        Map<?, ?> scope = steps.stream().map(PluginWorkflowTriggerTest::map)
                .filter(step -> "observability-performance-scope".equals(step.get("id")))
                .findFirst().orElseThrow();
        String command = (String) scope.get("run");
        assertThat(command).isNotBlank();
        if (expected) assertThat(root.resolve(changedPath)).isRegularFile();

        Path repository = Files.createDirectory(temporary.resolve("repository"));
        run(repository, Map.of(), "git", "init", "--quiet");
        run(repository, Map.of(), "git", "config", "user.name", "Workflow test fixture");
        run(repository, Map.of(), "git", "config", "user.email", "fixture@example.invalid");
        Files.writeString(repository.resolve("README.md"), "base fixture\n");
        commit(repository, "README.md");
        String base = run(repository, Map.of(), "git", "rev-parse", "HEAD").trim();
        Path changedFile = repository.resolve(changedPath);
        Files.createDirectories(changedFile.getParent());
        Files.writeString(changedFile, "isolated changed input\n");
        commit(repository, changedPath);
        String head = run(repository, Map.of(), "git", "rev-parse", "HEAD").trim();
        Path output = temporary.resolve("github-output.txt");

        // Execute the checked-in step, including Git's actual pathspec/exclusion semantics.
        run(repository, Map.of("PR_BASE_SHA", base, "PR_HEAD_SHA", head,
                        "GITHUB_OUTPUT", output.toString()),
                "bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", command);

        assertThat(output).isRegularFile();
        assertThat(Files.readAllLines(output)).containsExactly("run=" + expected);
    }

    @Test
    void fullPluginProfileIsAMandatoryMavenOwnedPrerequisiteForFullScope() throws Exception {
        Map<?, ?> jobs = map(workflow("ci-cd.yml").get("jobs"));
        assertThat(jobs.containsKey("plugin-profile"))
                .as("the full plugin-packaging-tests lifecycle needs a Docker-capable CI owner").isTrue();
        Map<?, ?> profile = map(jobs.get("plugin-profile"));
        assertThat(profile.get("needs")).isEqualTo(List.of("change-scope"));
        assertThat(profile.get("if")).isEqualTo("needs.change-scope.outputs.scope == 'full'");
        assertThat(profile.get("continue-on-error")).isNull();
        List<?> steps = (List<?>) profile.get("steps");
        assertThat(steps.stream().map(PluginWorkflowTriggerTest::map)
                .anyMatch(step -> "docker info".equals(step.get("run"))))
                .as("the complete developer profile also runs container-backed browser tests").isTrue();
        Map<?, ?> invocation = steps.stream().map(PluginWorkflowTriggerTest::map)
                .filter(step -> String.valueOf(step.get("run")).contains("-Pplugin-packaging-tests"))
                .findFirst().orElseThrow();
        assertThat(invocation.get("if")).isNull();
        assertThat(invocation.get("continue-on-error")).isNull();
        assertThat((String) invocation.get("run"))
                .contains("./mvnw -B verify -Pplugin-packaging-tests")
                .contains("ci-plugin-packaging")
                .doesNotContain("-Dtest=", "-Dit.test=", "-Dskip", "-DexcludedGroups=", "-Dtaxonomy.quality.skip=");
        Map<?, ?> verification = map(jobs.get("verify"));
        assertThat(((List<?>) verification.get("needs")).contains("plugin-profile")).isTrue();
        assertThat(((List<?>) verification.get("needs")).contains("change-scope")).isTrue();
        assertThat(verification.get("if")).isEqualTo("always()");
        assertThat(authoritativeLaneGate().get("if")).isEqualTo("needs.change-scope.outputs.scope == 'full'");
        assertThat(map(authoritativeLaneGate().get("env")).get("PLUGIN_PROFILE_RESULT"))
                .isEqualTo("${{ needs.plugin-profile.result }}");
    }

    @Test
    void draftHeadsFailAndEveryReadyScopeMustPassTheMavenOwnedGate() throws Exception {
        Map<?, ?> document = workflow("ci-cd.yml");
        Map<?, ?> events = map(document.containsKey("on") ? document.get("on") : document.get(true));
        assertThat(((List<?>) map(events.get("pull_request")).get("types")).stream()
                .map(String.class::cast).toList())
                .contains("ready_for_review", "converted_to_draft");
        Map<?, ?> verification = map(map(document.get("jobs")).get("verify"));
        List<?> steps = (List<?>) verification.get("steps");
        Map<?, ?> draft = steps.stream().map(PluginWorkflowTriggerTest::map)
                .filter(step -> "Keep draft heads non-mergeable".equals(step.get("name")))
                .findFirst().orElseThrow();
        assertThat(draft.get("if"))
                .isEqualTo("github.event_name == 'pull_request' && github.event.pull_request.draft == true");
        assertThat(draft.get("continue-on-error")).isNull();
        runExpectingExit(temporary, Map.of(), 1,
                "bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", (String) draft.get("run"));
        Map<?, ?> gate = steps.stream().map(PluginWorkflowTriggerTest::map)
                .filter(step -> "Require the complete selected verification scope".equals(step.get("name")))
                .findFirst().orElseThrow();
        assertThat(gate.get("if")).isNull();
        assertThat(gate.get("continue-on-error")).isNull();
        assertThat(gate.get("run")).isEqualTo("java -cp taxonomy-tooling/target/classes "
                + "com.taxonomy.tooling.TaxonomyTooling check-ci-verification-gate");
        assertThat(map(gate.get("env")).get("CI_NEEDS_JSON")).isEqualTo("${{ toJSON(needs) }}");
        assertThat(map(gate.get("env")).get("PR_DRAFT"))
                .isEqualTo("${{ github.event.pull_request.draft || false }}");
        assertThat(map(gate.get("env")).get("PR_BASE_SHA"))
                .isEqualTo("${{ github.event.pull_request.base.sha }}");
    }

    @Test
    void ciPackagingProfileSkipsOnlyTheDuplicateApplicationUnitTests() throws Exception {
        String rootPom = Files.readString(root.resolve("pom.xml"));
        String appPom = Files.readString(root.resolve("taxonomy-app/pom.xml"));
        String buildPom = Files.readString(root.resolve("taxonomy-build/pom.xml"));
        String packagingProfile = profile(rootPom, "plugin-packaging-tests");
        String ciPackagingProfile = profile(rootPom, "ci-plugin-packaging");
        String buildPackagingProfile = profile(buildPom, "plugin-packaging-tests");

        assertThat(packagingProfile)
                .contains("<skipITs>false</skipITs>")
                .contains("<it.test>ExternalPluginPackagedIT,OptionalFeaturePackagedIT</it.test>");
        assertThat(ciPackagingProfile)
                .contains("<taxonomy.app.unit.tests.skip>true</taxonomy.app.unit.tests.skip>")
                .contains("<taxonomy.app.shard.coverageAggregateSkip>true</taxonomy.app.shard.coverageAggregateSkip>")
                .contains("<taxonomy.coverage.policy.exclude>**/ReactorCoveragePolicyIT.java</taxonomy.coverage.policy.exclude>")
                .doesNotContain("<taxonomy.quality.skip>true</taxonomy.quality.skip>")
                .doesNotContain("<skipITs>true</skipITs>");
        assertThat(appPom).contains("<skip>${taxonomy.app.unit.tests.skip}</skip>");
        assertThat(buildPackagingProfile)
                .contains("<failIfNoTests>true</failIfNoTests>")
                .contains("<failIfNoSpecifiedTests>true</failIfNoSpecifiedTests>");
    }

    @ParameterizedTest(name = "full plugin profile result {0} -> aggregate exit {1}")
    @CsvSource({"success, 0", "failure, 1", "cancelled, 1", "skipped, 1", "'', 1"})
    void actualAggregateGateRejectsEveryIncompletePluginProfile(String result, int expectedExit)
            throws Exception {
        Map<?, ?> gate = authoritativeLaneGate();
        Map<String, String> environment = new HashMap<>();
        map(gate.get("env")).keySet().forEach(key -> environment.put(key.toString(), "success"));
        environment.put("PLUGIN_PROFILE_RESULT", result);
        runExpectingExit(temporary, environment, expectedExit,
                "bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", (String) gate.get("run"));
    }

    private Map<?, ?> authoritativeLaneGate() throws Exception {
        Map<?, ?> verification = map(map(workflow("ci-cd.yml").get("jobs")).get("verify"));
        return ((List<?>) verification.get("steps")).stream().map(PluginWorkflowTriggerTest::map)
                .filter(step -> "Require every authoritative lane".equals(step.get("name")))
                .findFirst().orElseThrow();
    }

    private void commit(Path repository, String file) throws Exception {
        run(repository, Map.of(), "git", "add", "--", file);
        run(repository, Map.of(), "git", "-c", "commit.gpgSign=false", "commit", "--quiet", "-m", "fixture");
    }

    private String run(Path directory, Map<String, String> environment, String... command) throws Exception {
        return runExpectingExit(directory, environment, 0, command);
    }

    private String runExpectingExit(Path directory, Map<String, String> environment,
                                   int expectedExit, String... command) throws Exception {
        Path log = temporary.resolve("command-output.txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        // A caller's GIT_DIR/INDEX_FILE or shell startup script must never redirect
        // fixture commands into the developer's checkout or load unrelated hooks.
        builder.environment().keySet().removeIf(key ->
                key.startsWith("GIT_") || key.equals("BASH_ENV") || key.equals("ENV"));
        builder.environment().putAll(Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_TERMINAL_PROMPT", "0"));
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("process completed: %s", List.of(command)).isTrue();
            String output = Files.readString(log);
            assertThat(process.exitValue()).as("process %s: %s", List.of(command), output).isEqualTo(expectedExit);
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private Map<?, ?> workflow(String name) throws Exception {
        try (var input = Files.newBufferedReader(root.resolve(".github/workflows").resolve(name))) {
            return map(new Yaml(new SafeConstructor(new LoaderOptions())).load(input));
        }
    }

    private static Map<?, ?> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<?, ?>) value;
    }

    private static String profile(String pom, String profileId) {
        int start = pom.indexOf("<id>" + profileId + "</id>");
        int end = pom.indexOf("</profile>", start);
        assertThat(start).as("profile %s exists", profileId).isGreaterThanOrEqualTo(0);
        assertThat(end).as("profile %s is closed", profileId).isGreaterThan(start);
        return pom.substring(start, end);
    }
}
