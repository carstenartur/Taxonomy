package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Maven-owned regression for the actual CI shell; no Docker or network is used. */
class CoreVerificationCleanlinessTest {
    @Test
    void cleanRunsBeforeContractsAndHelmEvidence() throws Exception {
        CoreVerificationCleanlinessChecks.assertCleanupOrder(workflow());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void successfulBuildsPreserveEvidenceForEveryEvent() throws Exception {
        for (String event : List.of("pull_request", "push", "workflow_dispatch")) {
            CoreVerificationCleanlinessChecks.assertEvidenceSurvives(workflow(), event, 0);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void failedBuildsPreserveDiagnosticsAndPropagateMavenExitStatus() throws Exception {
        for (String event : List.of("pull_request", "push", "workflow_dispatch")) {
            CoreVerificationCleanlinessChecks.assertEvidenceSurvives(workflow(), event, 23);
        }
    }

    private static String workflow() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (root != null) {
            Path workflow = root.resolve(".github/workflows/ci-cd.yml");
            if (Files.isRegularFile(workflow) && Files.isRegularFile(root.resolve("taxonomy-tooling/pom.xml"))) {
                return Files.readString(workflow);
            }
            root = root.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
