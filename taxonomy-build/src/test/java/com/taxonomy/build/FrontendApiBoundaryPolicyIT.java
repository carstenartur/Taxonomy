package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Full-checkout frontend transport boundary and monotonic-debt gate. */
class FrontendApiBoundaryPolicyIT {

    @Test
    void frontendTransportDebtDoesNotGrowOutsideTheApiLayer() {
        Path root = findRepositoryRoot();
        String baseRef = System.getenv("FRONTEND_API_BASE_REF");
        if (baseRef == null || baseRef.isBlank()) {
            baseRef = "HEAD^";
        }

        FrontendApiBoundaryPolicy policy = new FrontendApiBoundaryPolicy();
        FrontendApiBoundaryPolicy.Inspection inspection = policy.inspect(
                root, baseRef, new GitRevisionTextReader(root));
        Path report = root.resolve("target/frontend-api-boundary-report.txt");
        policy.writeReport(report, inspection.report());
        System.out.println(inspection.report());

        assertThat(inspection.passed())
                .as("frontend API boundary; inspect %s%n%s",
                        report, inspection.report())
                .isTrue();
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(
                    "taxonomy-app/src/main/resources/static/js"))
                    && Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
