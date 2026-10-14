package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.tools.ExecFileLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Post-reactor coverage gates executed by Failsafe in the final build-policy module. */
class ReactorCoveragePolicyIT {

    @Test
    void authoritativeCoverageMeetsAggregateCriticalAndDiffPoliciesAndPublishesEvidence()
            throws Exception {
        Path root = findRepositoryRoot();
        if (Boolean.parseBoolean(System.getProperty("taxonomy.app.shard.coverage.enabled"))) {
            assertShardExecutionsWereMerged(root);
        }
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

    private static void assertShardExecutionsWereMerged(Path root) throws Exception {
        Path shardRoot = root.resolve("target/app-test-shards");
        Path mergedFile = root.resolve(
                "taxonomy-app/target/jacoco-shards-merged.exec");
        Map<Long, boolean[]> expectedProbes = new HashMap<>();
        try (var shards = Files.list(shardRoot)) {
            for (Path shard : shards.filter(Files::isDirectory).toList()) {
                Path executionFile = shard.resolve("coverage.exec");
                assertThat(executionFile)
                        .as("real JaCoCo execution data for %s", shard.getFileName())
                        .isRegularFile();
                ExecFileLoader input = new ExecFileLoader();
                input.load(executionFile.toFile());
                for (ExecutionData data : input.getExecutionDataStore().getContents()) {
                    boolean[] probes = expectedProbes.computeIfAbsent(data.getId(),
                            ignored -> new boolean[data.getProbes().length]);
                    assertThat(probes).hasSize(data.getProbes().length);
                    for (int index = 0; index < probes.length; index++) {
                        probes[index] |= data.getProbes()[index];
                    }
                }
            }
        }
        assertThat(expectedProbes).isNotEmpty();
        assertThat(expectedProbes.values().stream()
                .flatMapToInt(probes -> java.util.stream.IntStream.range(0, probes.length)
                        .filter(index -> probes[index]))
                .count()).isPositive();

        assertThat(mergedFile)
                .as("JaCoCo Maven merge output")
                .isRegularFile();
        ExecFileLoader merged = new ExecFileLoader();
        merged.load(mergedFile.toFile());
        Map<Long, ExecutionData> mergedData = new HashMap<>();
        for (ExecutionData data : merged.getExecutionDataStore().getContents()) {
            mergedData.put(data.getId(), data);
        }
        expectedProbes.forEach((classId, probes) -> {
            ExecutionData actual = mergedData.get(classId);
            assertThat(actual).as("merged execution class id %s", classId).isNotNull();
            assertThat(actual.getProbes()).hasSize(probes.length);
            for (int index = 0; index < probes.length; index++) {
                if (probes[index]) {
                    assertThat(actual.getProbes()[index])
                            .as("merged probe %s for class id %s", index, classId)
                            .isTrue();
                }
            }
        });
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
