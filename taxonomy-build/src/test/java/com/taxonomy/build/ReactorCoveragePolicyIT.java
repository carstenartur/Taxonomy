package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Post-reactor coverage gate executed by Failsafe in the final build-policy module. */
class ReactorCoveragePolicyIT {

    @Test
    void authoritativeAggregateCoverageMeetsTheVersionedPolicyAndPublishesEvidence()
            throws Exception {
        Path root = findRepositoryRoot();
        Path xml = root.resolve(
                "taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml");
        Path policyFile = root.resolve(".github/coverage-policy.json");
        Path evidence = root.resolve("target/coverage-gate.txt");

        assertThat(xml)
                .as("authoritative aggregate JaCoCo XML")
                .isRegularFile();
        assertThat(policyFile)
                .as("versioned coverage policy")
                .isRegularFile();

        ReactorCoveragePolicy evaluator = new ReactorCoveragePolicy();
        ReactorCoveragePolicy.Evaluation evaluation = evaluator.evaluate(
                xml, evaluator.loadPolicy(policyFile));
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, evaluation.text());
        System.out.print(evaluation.text());

        assertThat(evaluation.passed())
                .as("reactor coverage policy; inspect %s", evidence)
                .isTrue();
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(".github/coverage-policy.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
