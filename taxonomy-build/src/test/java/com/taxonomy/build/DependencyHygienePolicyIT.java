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

    @Test
    void resolvedEmbeddedTomcatFamilyMeetsTheSecurityFloor() {
        Path root = findRepositoryRoot();
        // Read the generated aggregate directly: an unrelated fallback SBOM
        // must not satisfy the embedded-server acceptance gate.
        var tomcat = new DependencyHygieneInputs()
                .readComponents(root.resolve("target/taxonomy-sbom.json"))
                .stream()
                .filter(component -> "org.apache.tomcat.embed".equals(component.group()))
                .toList();

        assertThat(tomcat)
                .extracting(DependencyHygienePolicy.Component::name)
                .contains("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket");
        var versions = tomcat.stream()
                .map(DependencyHygienePolicy.Component::version)
                .distinct()
                .toList();
        assertThat(versions).as("embedded Tomcat modules must remain aligned").hasSize(1);
        String version = versions.getFirst();
        assertThat(version).as("a released Tomcat 11.0.x servlet stack").matches("11\\.0\\.\\d+");
        assertThat(Integer.parseInt(version.substring("11.0.".length())))
                .as("Tomcat security fixes for CVE-2026-65182, CVE-2026-65905 and CVE-2026-68525")
                .isGreaterThanOrEqualTo(25);
        System.out.println("Resolved embedded Tomcat security baseline: " + version);
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
