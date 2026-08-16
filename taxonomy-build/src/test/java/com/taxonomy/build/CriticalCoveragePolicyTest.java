package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CriticalCoveragePolicyTest {

    private final CriticalCoveragePolicy evaluator = new CriticalCoveragePolicy();

    @Test
    void packageBudgetsPassAndRenderEvidence(@TempDir Path root) throws Exception {
        Path xml = write(root.resolve("jacoco.xml"), reportXml(
                90, 10, 80, 20,
                90, 10, 80, 20));

        CriticalCoveragePolicy.Evaluation evaluation = evaluator.evaluate(
                xml,
                policy(0.80, 0.65, 0.75, 0.60, List.of()),
                new CriticalCoveragePolicy.ChangedSources(Set.of(), "none"));

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.text())
                .contains("package:taxonomy-app:com/taxonomy/security")
                .contains("LINE: 90.00% (90/100); required 80.00%; PASS")
                .contains("BRANCH: 80.00% (80/100); required 65.00%; PASS")
                .contains("Result: PASS");
    }

    @Test
    void missingOrLowPackageFailsClosed(@TempDir Path root) throws Exception {
        Path lowXml = write(root.resolve("low.xml"), reportXml(
                79, 21, 64, 36,
                90, 10, 80, 20));
        Path missingXml = write(root.resolve("missing.xml"), """
                <report name="critical">
                  <group name="taxonomy-app">
                    <package name="com/taxonomy/other">
                      <counter type="LINE" missed="10" covered="90"/>
                      <counter type="BRANCH" missed="20" covered="80"/>
                    </package>
                  </group>
                </report>
                """);

        CriticalCoveragePolicy.Evaluation low = evaluator.evaluate(
                lowXml,
                policy(0.80, 0.65, 0.75, 0.60, List.of()),
                new CriticalCoveragePolicy.ChangedSources(Set.of(), "none"));
        CriticalCoveragePolicy.Evaluation missing = evaluator.evaluate(
                missingXml,
                policy(0.80, 0.65, 0.75, 0.60, List.of()),
                new CriticalCoveragePolicy.ChangedSources(Set.of(), "none"));

        assertThat(low.passed()).isFalse();
        assertThat(low.text())
                .contains("LINE coverage 79.00% is below 80.00%")
                .contains("BRANCH coverage 64.00% is below 65.00%");
        assertThat(missing.passed()).isFalse();
        assertThat(missing.text()).contains(
                "package is absent from the authoritative JaCoCo report");
    }

    @Test
    void changedCriticalSourceMustMeetItsBudgetsAndBePresent(@TempDir Path root)
            throws Exception {
        Path xml = write(root.resolve("jacoco.xml"), reportXml(
                90, 10, 80, 20,
                70, 30, 50, 50));

        CriticalCoveragePolicy.Evaluation lowCoverage = evaluator.evaluate(
                xml,
                policy(0.80, 0.65, 0.75, 0.60, List.of()),
                new CriticalCoveragePolicy.ChangedSources(
                        Set.of("taxonomy-app/src/main/java/com/taxonomy/security/Foo.java"),
                        "test diff"));
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
                .contains("BRANCH: N/A (source has no executable branch counter total)");
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
    void malformedPolicyAndReportFailClosed(@TempDir Path root) throws Exception {
        Path duplicatePolicy = write(root.resolve("duplicate.json"), """
                {
                  "schemaVersion": 1,
                  "requiredCounters": ["LINE", "BRANCH"],
                  "changedSourceMinimums": {"LINE": 0.75, "BRANCH": 0.60},
                  "changedSourcePrefixes": ["taxonomy-app/src/main/java/com/taxonomy/security/"],
                  "criticalPackages": [
                    {
                      "module": "taxonomy-app",
                      "package": "com/taxonomy/security",
                      "minimums": {"LINE": 0.80, "BRANCH": 0.65}
                    },
                    {
                      "module": "taxonomy-app",
                      "package": "com/taxonomy/security",
                      "minimums": {"LINE": 0.80, "BRANCH": 0.65}
                    }
                  ],
                  "temporaryExceptions": []
                }
                """);
        Path malformedReport = write(root.resolve("malformed.xml"), """
                <report name="critical">
                  <group name="taxonomy-app">
                    <package name="com/taxonomy/security">
                      <counter type="LINE" missed="10" covered="90"/>
                      <counter type="LINE" missed="20" covered="80"/>
                      <counter type="BRANCH" missed="20" covered="80"/>
                    </package>
                  </group>
                </report>
                """);
        Path externalEntity = write(root.resolve("external.xml"), """
                <!DOCTYPE report [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <report name="&xxe;">
                  <group name="taxonomy-app"/>
                </report>
                """);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> evaluator.loadPolicy(
                        duplicatePolicy, LocalDate.of(2026, 8, 15)))
                .withMessageContaining("duplicate");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> evaluator.parseReport(
                        malformedReport, CriticalCoveragePolicy.COUNTER_TYPES))
                .withMessageContaining("Duplicate LINE counter");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> evaluator.parseReport(
                        externalEntity, CriticalCoveragePolicy.COUNTER_TYPES))
                .withMessageContaining("Unsupported DOCTYPE");
    }

    @Test
    void changeDiscoveryIsRepositoryRelativeAndCriticalSelectionIsExplicit(
            @TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".git"));
        CriticalCoveragePolicy.ChangedSources discovered =
                new CriticalCoveragePolicy.ChangedSources(
                        Set.of(
                                "taxonomy-app/src/main/java/com/taxonomy/security/SecurityConfig.java",
                                "taxonomy-app/src/main/java/com/taxonomy/catalog/TaxonomyService.java"),
                        "test discovery");

        CriticalCoveragePolicy.ChangedSources selected = evaluator.selectCriticalSources(
                discovered,
                List.of("taxonomy-app/src/main/java/com/taxonomy/security/"));

        assertThat(selected.paths()).containsExactly(
                "taxonomy-app/src/main/java/com/taxonomy/security/SecurityConfig.java");
        assertThat(selected.description()).contains("1 file(s) are release-critical");
    }

    private static CriticalCoveragePolicy.CoveragePolicy policy(
            double packageLine,
            double packageBranch,
            double sourceLine,
            double sourceBranch,
            List<CriticalCoveragePolicy.TemporaryException> exceptions) {
        return new CriticalCoveragePolicy.CoveragePolicy(
                CriticalCoveragePolicy.COUNTER_TYPES,
                Map.of("LINE", sourceLine, "BRANCH", sourceBranch),
                List.of("taxonomy-app/src/main/java/com/taxonomy/security/"),
                List.of(new CriticalCoveragePolicy.PackageBudget(
                        new CriticalCoveragePolicy.PackageKey(
                                "taxonomy-app", "com/taxonomy/security"),
                        Map.of("LINE", packageLine, "BRANCH", packageBranch))),
                exceptions);
    }

    private static String reportXml(
            int packageLineCovered,
            int packageLineMissed,
            int packageBranchCovered,
            int packageBranchMissed,
            int sourceLineCovered,
            int sourceLineMissed,
            int sourceBranchCovered,
            int sourceBranchMissed) {
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
                sourceLineMissed,
                sourceLineCovered,
                sourceBranchMissed,
                sourceBranchCovered,
                packageLineMissed,
                packageLineCovered,
                packageBranchMissed,
                packageBranchCovered);
    }

    private static String policyJson(String expiresOn) {
        return """
                {
                  "schemaVersion": 1,
                  "requiredCounters": ["LINE", "BRANCH"],
                  "changedSourceMinimums": {"LINE": 0.75, "BRANCH": 0.60},
                  "changedSourcePrefixes": ["taxonomy-app/src/main/java/com/taxonomy/security/"],
                  "criticalPackages": [
                    {
                      "module": "taxonomy-app",
                      "package": "com/taxonomy/security",
                      "minimums": {"LINE": 0.80, "BRANCH": 0.65}
                    }
                  ],
                  "temporaryExceptions": [
                    {
                      "scope": "package:taxonomy-app:com/taxonomy/security",
                      "rationale": "Temporary branch gap",
                      "owner": "security-maintainers",
                      "expiresOn": "%s"
                    }
                  ]
                }
                """.formatted(expiresOn);
    }

    private static Path write(Path path, String content) throws Exception {
        Files.writeString(path, content);
        return path;
    }
}
