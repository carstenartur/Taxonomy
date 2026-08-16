package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Post-reactor coverage gates executed by Failsafe in the final build-policy module. */
class ReactorCoveragePolicyIT {

    @Test
    void authoritativeCoverageMeetsAggregateCriticalAndDiffPoliciesAndPublishesEvidence()
            throws Exception {
        Path root = findRepositoryRoot();
        Path xml = root.resolve(
                "taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml");
        Path aggregatePolicyFile = root.resolve(".github/coverage-policy.json");
        Path criticalPolicyFile = root.resolve(
                ".github/critical-coverage-policy.json");
        Path evidence = root.resolve("target/coverage-gate.txt");

        assertThat(xml)
                .as("authoritative aggregate JaCoCo XML")
                .isRegularFile();
        assertThat(aggregatePolicyFile)
                .as("versioned aggregate coverage policy")
                .isRegularFile();
        assertThat(criticalPolicyFile)
                .as("versioned release-critical coverage policy")
                .isRegularFile();

        ReactorCoveragePolicy aggregateEvaluator = new ReactorCoveragePolicy();
        ReactorCoveragePolicy.Evaluation aggregateEvaluation =
                aggregateEvaluator.evaluate(
                        xml, aggregateEvaluator.loadPolicy(aggregatePolicyFile));

        CriticalCoveragePolicy criticalEvaluator = new CriticalCoveragePolicy();
        CriticalCoveragePolicy.CoveragePolicy criticalPolicy =
                criticalEvaluator.loadPolicy(criticalPolicyFile);
        CriticalCoveragePolicy.ChangedSources discovered =
                criticalEvaluator.discoverChangedSources(
                        root, System.getenv("GITHUB_BASE_REF"));
        CriticalCoveragePolicy.ChangedSources selected =
                criticalEvaluator.selectCriticalSources(
                        discovered, criticalPolicy.changedSourcePrefixes());
        CriticalCoveragePolicy.Evaluation criticalEvaluation =
                criticalEvaluator.evaluate(xml, criticalPolicy, selected);

        String report = aggregateEvaluation.text() + criticalEvaluation.text();
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, report);
        System.out.print(report);

        assertThat(aggregateEvaluation.passed())
                .as("reactor coverage policy; inspect %s", evidence)
                .isTrue();
        assertThat(criticalEvaluation.passed())
                .as("release-critical coverage policy; inspect %s", evidence)
                .isTrue();
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(".github/coverage-policy.json"))
                    && Files.isRegularFile(current.resolve(
                            ".github/critical-coverage-policy.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
