package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CriticalCoveragePolicyTest {

    private final CriticalCoveragePolicy evaluator = new CriticalCoveragePolicy();

    @Test
    void packageAndChangedSourceRatchetsPassAndPublishEvidence(@TempDir Path root)
            throws Exception {
        Path xml = write(root.resolve("jacoco.xml"), reportXml(
                82, 18, 66, 34,
                80, 20, 60, 40));
        CriticalCoveragePolicy.CoveragePolicy policy = policy(
                0.80, 0.65, 0.75, 0.60, List.of());
        CriticalCoveragePolicy.ChangedSources changed =
                new CriticalCoveragePolicy.ChangedSources(
                        Set.of("taxonomy-app/src/main/java/com/taxonomy/security/Foo.java"),
                        "test diff");

        CriticalCoveragePolicy.Evaluation evaluation = evaluator.evaluate(
                xml, policy, changed);

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.text())
                .contains("package:taxonomy-app:com/taxonomy/security")
                .contains("taxonomy-app/src/main/java/com/taxonomy/security/Foo.java")
                .contains("Changed critical source coverage")
                .contains("Result: PASS");
    }

    @Test
    void packageBranchRegressionFailsEvenWhenLineCoveragePasses(@TempDir Path root)
            throws Exception {
        Path xml = write(root.resolve("jacoco.xml"), reportXml(
                90, 10, 64, 36,
                90, 10, 80, 20));

        CriticalCoveragePolicy.Evaluation evaluation = evaluator.evaluate(
                xml,
                policy(0.80, 0.65, 0.75, 0.60, List.of()),
                new CriticalCoveragePolicy.ChangedSources(Set.of(), "none"));

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.text())
                .contains("BRANCH coverage 64.00% is below 65.00%")
                .contains("Result: FAIL");
    }

    @Test
    void changedCriticalSourceMustBePresentAndMeetItsOwnBudget(@TempDir Path root)
            throws Exception {
        Path xml = write(root.resolve("jacoco.xml"), reportXml(
                90, 10, 80, 20,
                70, 30, 50, 50));
        String source = "taxonomy-app/src/main/java/com/taxonomy/security/Foo.java";

        CriticalCoveragePolicy.Evaluation lowCoverage = evaluator.evaluate(
                xml,
                policy(0.80, 0.65, 0.75, 0.60, List.of()),
                new CriticalCoveragePolicy.ChangedSources(Set.of(source), "test diff"));
        CriticalCoveragePolicy.Evaluation missing = evaluator.evaluate(
                xml,
                policy(0.80, 0.65, 0.75, 0.60, List.of()),
                new CriticalCoveragePolicy.ChangedSources(
                        Set.of("taxonomy-app/src/main/java/com/taxonomy/security/Missing.java"),
                        "test diff"));

        assertThat(lowCoverage.passed()).isFalse();
        assertThat(lowCoverage.text())
                .contains("LINE coverage 70.00% is below 75.00%")
                .contains("BRANCH coverage 50.00% is below 60.00%");
        assertThat(missing.passed()).isFalse();
        assertThat(missing.text()).contains(
                "changed source is absent from the authoritative JaCoCo report");
    }

    @Test
    void changedSourceWithoutABranchCounterUsesLineCoverageAndReportsNotApplicable(
            @TempDir Path root) throws Exception {
        Path xml = write(root.resolve("jacoco.xml"), """
                <report name="critical">
                  <group name="taxonomy-app">
                    <package name="com/taxonomy/security">
                      <sourcefile name="Foo.java">
                        <counter type="LINE" missed="20" covered="80"/>
                      </sourcefile>
                      <counter type="LINE" missed="10" covered="90"/>
                      <counter type="BRANCH" missed="20" covered="80"/>
                    </package>
                  </group>
                </report>
                """);

        CriticalCoveragePolicy.Evaluation evaluation = evaluator.evaluate(
                xml,
                policy(0.80, 0.65, 0.75, 0.60, List.of()),
                new CriticalCoveragePolicy.ChangedSources(
                        Set.of("taxonomy-app/src/main/java/com/taxonomy/security/Foo.java"),
                        "test diff"));

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.text())
                .contains("BRANCH: N/A (source has no branch counter total)");
    }

    @Test
    void activeExceptionIsOwnedAndVisibleButExpiredExceptionFailsPolicyLoading(
            @TempDir Path root) throws Exception {
        Path xml = write(root.resolve("jacoco.xml"), reportXml(
                90, 10, 50, 50,
                90, 10, 80, 20));
        String scope = "package:taxonomy-app:com/taxonomy/security";
        CriticalCoveragePolicy.TemporaryException exception =
                new CriticalCoveragePolicy.TemporaryException(
                        scope,
                        "Temporary branch gap while the security adapter is decomposed",
                        "security-maintainers",
                        LocalDate.of(2099, 1, 1));

        CriticalCoveragePolicy.Evaluation evaluation = evaluator.evaluate(
                xml,
                policy(0.80, 0.65, 0.75, 0.60, List.of(exception)),
                new CriticalCoveragePolicy.ChangedSources(Set.of(), "none"));
        Path expiredPolicy = write(root.resolve("expired.json"), policyJson("2000-01-01"));

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.text())
                .contains("owner security-maintainers")
                .contains("APPLIED");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> evaluator.loadPolicy(
                        expiredPolicy, LocalDate.of(2026, 8, 15)))
                .withMessageContaining("expired on 2000-01-01");
    }

    @Test
    void realGitDiffDiscoverySelectsOnlyCriticalProductionSources(@TempDir Path root)
            throws Exception {
        runGit(root, "init");
        runGit(root, "config", "user.name", "Coverage Test");
        runGit(root, "config", "user.email", "coverage@example.invalid");
        Path critical = root.resolve(
                "taxonomy-app/src/main/java/com/taxonomy/security/Foo.java");
        Path unrelated = root.resolve(
                "taxonomy-app/src/main/java/com/taxonomy/ui/Bar.java");
        write(critical, "package com.taxonomy.security; class Foo {}\n");
        write(unrelated, "package com.taxonomy.ui; class Bar {}\n");
        runGit(root, "add", ".");
        runGit(root, "commit", "-m", "base");
        String baseCommit = runGit(root, "rev-parse", "HEAD").strip();

        write(critical,
                "package com.taxonomy.security; class Foo { boolean secure() { return true; } }\n");
        write(unrelated,
                "package com.taxonomy.ui; class Bar { int value() { return 1; } }\n");
        runGit(root, "add", ".");
        runGit(root, "commit", "-m", "changed");

        CriticalCoveragePolicy.ChangedSources discovered =
                evaluator.discoverChangedSources(root, baseCommit);
        CriticalCoveragePolicy.ChangedSources selected = evaluator.selectCriticalSources(
                discovered,
                List.of("taxonomy-app/src/main/java/com/taxonomy/security/"));

        assertThat(discovered.paths()).containsExactlyInAnyOrder(
                "taxonomy-app/src/main/java/com/taxonomy/security/Foo.java",
                "taxonomy-app/src/main/java/com/taxonomy/ui/Bar.java");
        assertThat(selected.paths()).containsExactly(
                "taxonomy-app/src/main/java/com/taxonomy/security/Foo.java");
    }

    @Test
    void policyRejectsUnsafePrefixesAndIncompleteExceptions(@TempDir Path root)
            throws Exception {
        Path unsafePrefix = write(root.resolve("unsafe-prefix.json"), policyJsonWithPrefix(
                "../taxonomy-app/src/main/java/", "[]"));
        Path incompleteException = write(root.resolve("incomplete-exception.json"),
                policyJsonWithPrefix(
                        "taxonomy-app/src/main/java/com/taxonomy/security/",
                        "[{\"scope\":\"package:taxonomy-app:com/taxonomy/security\","
                                + "\"owner\":\"security\",\"expiresOn\":\"2099-01-01\"}]"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> evaluator.loadPolicy(unsafePrefix))
                .withMessageContaining("repository-relative directories");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> evaluator.loadPolicy(incompleteException))
                .withMessageContaining("rationale must be a non-empty string");
    }

    private static CriticalCoveragePolicy.CoveragePolicy policy(
            double packageLine,
            double packageBranch,
            double changedLine,
            double changedBranch,
            List<CriticalCoveragePolicy.TemporaryException> exceptions) {
        return new CriticalCoveragePolicy.CoveragePolicy(
                CriticalCoveragePolicy.COUNTER_TYPES,
                Map.of("LINE", changedLine, "BRANCH", changedBranch),
                List.of("taxonomy-app/src/main/java/com/taxonomy/security/"),
                List.of(new CriticalCoveragePolicy.PackageBudget(
                        new CriticalCoveragePolicy.PackageKey(
                                "taxonomy-app", "com/taxonomy/security"),
                        Map.of("LINE", packageLine, "BRANCH", packageBranch))),
                exceptions);
    }

    private static String reportXml(
            long packageLineCovered,
            long packageLineMissed,
            long packageBranchCovered,
            long packageBranchMissed,
            long sourceLineCovered,
            long sourceLineMissed,
            long sourceBranchCovered,
            long sourceBranchMissed) {
        return """
                <report name="critical">
                  <group name="taxonomy-app">
                    <package name="com/taxonomy/security">
                      <sourcefile name="Foo.java">
                        <counter type="LINE" missed="%d" covered="%d"/>
                        <counter type="BRANCH" missed="%d" covered="%d"/>
                      </sourcefile>
                      <counter type="LINE" missed="%d" covered="%d"/>
                      <counter type="BRANCH" missed="%d" covered="%d"/>
                    </package>
                  </group>
                </report>
                """.formatted(
                        sourceLineMissed, sourceLineCovered,
                        sourceBranchMissed, sourceBranchCovered,
                        packageLineMissed, packageLineCovered,
                        packageBranchMissed, packageBranchCovered);
    }

    private static String policyJson(String expiresOn) {
        return policyJsonWithPrefix(
                "taxonomy-app/src/main/java/com/taxonomy/security/",
                "[{\"scope\":\"package:taxonomy-app:com/taxonomy/security\","
                        + "\"rationale\":\"temporary\",\"owner\":\"security\","
                        + "\"expiresOn\":\"" + expiresOn + "\"}]");
    }

    private static String policyJsonWithPrefix(String prefix, String exceptions) {
        return """
                {
                  "schemaVersion": 1,
                  "requiredCounters": ["LINE", "BRANCH"],
                  "changedSourceMinimums": {"LINE": 0.75, "BRANCH": 0.60},
                  "changedSourcePrefixes": ["%s"],
                  "criticalPackages": [
                    {
                      "module": "taxonomy-app",
                      "package": "com/taxonomy/security",
                      "minimums": {"LINE": 0.80, "BRANCH": 0.65}
                    }
                  ],
                  "temporaryExceptions": %s
                }
                """.formatted(prefix, exceptions);
    }

    private static Path write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static String runGit(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertThat(exitCode).as("%s%n%s", command, output).isZero();
        return output;
    }
}
