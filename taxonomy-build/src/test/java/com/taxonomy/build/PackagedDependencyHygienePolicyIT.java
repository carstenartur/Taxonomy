package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Post-reactor decision over the aggregate CycloneDX SBOM. */
class PackagedDependencyHygienePolicyIT {

    @Test
    void packagedDependenciesSatisfyTheVersionedPdfExportContract() {
        Path root = findRepositoryRoot();
        Path sbom = root.resolve("target/taxonomy-sbom.json");
        Path reportPath = root.resolve("target/dependency-hygiene-report.txt");
        String expectedPdfboxVersion = System.getProperty("pdfbox.version", "").strip();

        PackagedDependencyHygienePolicy policy =
                new PackagedDependencyHygienePolicy();
        PackagedDependencyHygienePolicy.Inspection inspection =
                policy.inspect(sbom, expectedPdfboxVersion);
        String report = policy.report(sbom, inspection);
        policy.writeReport(reportPath, report);
        System.out.println(report);

        assertThat(inspection.passed())
                .as("packaged dependency hygiene; inspect %s%n%s",
                        reportPath, report)
                .isTrue();
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("taxonomy-build"))
                    && Files.isDirectory(current.resolve("taxonomy-app"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
