package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Post-reactor gate for the packaged CycloneDX dependency set. */
class DependencyHygienePolicyIT {

    @Test
    void packagedApplicationDependenciesMatchTheVersionedPolicy() {
        Path root = findRepositoryRoot();
        Path report = root.resolve("target/dependency-hygiene-report.txt");
        DependencyHygienePolicy policy = new DependencyHygienePolicy();
        DependencyHygieneInputs inputs = new DependencyHygieneInputs();

        try {
            DependencyHygieneInputs.MaterializedSbom sbom =
                    inputs.materializeRequestedSbom(
                            root, root.resolve("target/taxonomy-sbom.json"));
            var exceptions = inputs.loadExceptions(
                    root.resolve(".github/dependency-hygiene-exceptions.json"),
                    LocalDate.now(ZoneOffset.UTC));
            String expectedVersion = System.getProperty("pdfbox.version");
            DependencyHygienePolicy.Evaluation evaluation = policy.evaluate(
                    sbom.components(), exceptions, expectedVersion);
            policy.writeReport(report, evaluation.report());
            System.out.println("Dependency SBOM source: " + sbom.sourcePath());
            System.out.println(evaluation.report());

            assertThat(evaluation.passed())
                    .as("packaged dependency hygiene; inspect %s%n%s",
                            report, evaluation.report())
                    .isTrue();
        } catch (RuntimeException error) {
            String failure = "Taxonomy packaged dependency hygiene\n\n"
                    + "Policy evaluation error: "
                    + error.getClass().getSimpleName() + ": "
                    + error.getMessage() + "\n\nResult: FAIL\n";
            policy.writeReport(report, failure);
            throw error;
        }
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            ".github/dependency-hygiene-exceptions.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
