package com.taxonomy;

import com.taxonomy.acceptance.ScenarioJsonFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioSnapshotObservationTest {
    @TempDir Path directory;

    @Test void firstReadRetainsEveryNestedFieldAndTheOriginalProgress() {
        var json = new ObjectMapper();
        var snapshot = json.createObjectNode();
        snapshot.putObject("summary").put("id", "original");
        var analysis = snapshot.putObject("analysis");
        analysis.putObject("relationSearchReport").putObject("progress")
                .put("assessedSources", 75).put("completedSearches", 399);
        analysis.putObject("recovery").put("state", "STOPPED");
        analysis.putArray("completeEvidence").addObject().put("source", "Quoted 水位 🌊 evidence");
        var observation = ScenarioSnapshotObservation.observe("original", snapshot);
        assertThat(observation.id()).isEqualTo("original");
        assertThat(observation.sha256()).isEqualTo(ScenarioJsonFingerprint.sha256(snapshot));
        assertThat(observation.assessedSources()).isEqualTo(75);
        assertThat(observation.completedSearches()).isEqualTo(399);
        assertThat(observation.recoveryState().asText()).isEqualTo("STOPPED");
        var changed = snapshot.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) changed.at("/analysis/completeEvidence").get(0))
                .put("source", "Changed deeply nested evidence");
        assertThat(ScenarioJsonFingerprint.sha256(changed)).isNotEqualTo(observation.sha256());
        String firstFingerprint = observation.sha256();
        ((tools.jackson.databind.node.ObjectNode) analysis.at("/relationSearchReport/progress"))
                .put("assessedSources", 999);
        ((tools.jackson.databind.node.ObjectNode) analysis.path("recovery")).put("state", "COMPLETED");
        ((tools.jackson.databind.node.ObjectNode) analysis.path("completeEvidence").get(0))
                .put("source", "Mutated original node");
        assertThat(observation.sha256()).isEqualTo(firstFingerprint)
                .isNotEqualTo(ScenarioJsonFingerprint.sha256(snapshot));
        assertThat(observation.assessedSources()).isEqualTo(75);
        assertThat(observation.completedSearches()).isEqualTo(399);
        assertThat(observation.recoveryState().asText()).isEqualTo("STOPPED");
    }

    @Test void observingTwoIndependentPassesDoesNotRetainTheirCompleteCheckpointedPayloads() throws Exception {
        var log = directory.resolve("observations.log");
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName());
        Process child = command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(child.waitFor(60, TimeUnit.SECONDS)).as("Bounded snapshot observation probe finishes").isTrue();
            String diagnostics = Files.readString(log);
            assertThat(child.exitValue()).as(diagnostics).isZero();
            assertThat(diagnostics).contains("SNAPSHOT_OBSERVATIONS_OK 8");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    public static class Probe {
        public static void main(String[] args) {
            var json = new ObjectMapper();
            var latest = new ScenarioSnapshotObservation[2];
            for (int cycle = 0; cycle < 8; cycle++) {
                var snapshot = json.createObjectNode().put("cycle", cycle).put("completeEvidence", "水".repeat(12_000_000));
                String id = "snapshot-" + cycle;
                var observation = ScenarioSnapshotObservation.observe(id, snapshot);
                if (!observation.sha256().equals(ScenarioJsonFingerprint.sha256(snapshot)))
                    throw new AssertionError("Every complete snapshot field must contribute to the observation");
                latest[cycle % 2] = observation;
                System.out.println("Observed " + id);
            }
            if (!latest[0].id().equals("snapshot-6") || !latest[1].id().equals("snapshot-7"))
                throw new AssertionError("Both original passes retain their latest first-read identity");
            System.out.println("SNAPSHOT_OBSERVATIONS_OK 8");
        }
    }
}
