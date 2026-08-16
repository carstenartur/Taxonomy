package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CriticalCoverageSparseReportTest {

    private final CriticalCoveragePolicy evaluator = new CriticalCoveragePolicy();

    @Test
    void ignoresUnmeasuredUnrelatedPackagesAndSources(@TempDir Path root) throws Exception {
        Path xml = root.resolve("jacoco.xml");
        Files.writeString(xml, """
                <report name="critical">
                  <group name="taxonomy-domain">
                    <package name="com/taxonomy/pipeline">
                      <sourcefile name="PipelineMarker.java"/>
                    </package>
                  </group>
                  <group name="taxonomy-app">
                    <package name="com/taxonomy/security">
                      <sourcefile name="Foo.java">
                        <counter type="LINE" missed="10" covered="90"/>
                        <counter type="BRANCH" missed="20" covered="80"/>
                      </sourcefile>
                      <counter type="LINE" missed="10" covered="90"/>
                      <counter type="BRANCH" missed="20" covered="80"/>
                    </package>
                  </group>
                </report>
                """);

        CriticalCoveragePolicy.Evaluation evaluation = evaluator.evaluate(
                xml,
                policy(),
                new CriticalCoveragePolicy.ChangedSources(Set.of(), "none"));

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.text()).contains("Result: PASS");
    }

    @Test
    void branchlessCriticalPackageFailsAsCoverageNotAsMalformedXml(@TempDir Path root)
            throws Exception {
        Path xml = root.resolve("jacoco.xml");
        Files.writeString(xml, """
                <report name="critical">
                  <group name="taxonomy-app">
                    <package name="com/taxonomy/security">
                      <sourcefile name="Foo.java">
                        <counter type="LINE" missed="10" covered="90"/>
                      </sourcefile>
                      <counter type="LINE" missed="10" covered="90"/>
                    </package>
                  </group>
                </report>
                """);

        CriticalCoveragePolicy.Evaluation evaluation = evaluator.evaluate(
                xml,
                policy(),
                new CriticalCoveragePolicy.ChangedSources(Set.of(), "none"));

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.text())
                .contains("BRANCH: N/A (0/0)")
                .contains("BRANCH coverage 0.00% is below 60.00%")
                .contains("Result: FAIL");
    }

    private static CriticalCoveragePolicy.CoveragePolicy policy() {
        return new CriticalCoveragePolicy.CoveragePolicy(
                CriticalCoveragePolicy.COUNTER_TYPES,
                Map.of("LINE", 0.75, "BRANCH", 0.60),
                List.of("taxonomy-app/src/main/java/com/taxonomy/security/"),
                List.of(new CriticalCoveragePolicy.PackageBudget(
                        new CriticalCoveragePolicy.PackageKey(
                                "taxonomy-app", "com/taxonomy/security"),
                        Map.of("LINE", 0.80, "BRANCH", 0.60))),
                List.of());
    }
}
