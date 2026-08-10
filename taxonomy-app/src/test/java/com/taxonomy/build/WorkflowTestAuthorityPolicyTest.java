package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowTestAuthorityPolicyTest {

    private final WorkflowTestAuthorityPolicy policy = new WorkflowTestAuthorityPolicy();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void repositoryWorkflowsDelegateExecutableTestsToMaven() throws Exception {
        WorkflowTestAuthorityPolicy.Inspection inspection =
                policy.inspect(findRepositoryRoot());

        assertThat(inspection.workflowCount()).isPositive();
        assertThat(inspection.errors())
                .as("workflow test-authority policy violations")
                .isEmpty();
    }

    @Test
    void reportsDirectWorkflowOwnedTestsAndMissingDatabaseProfiles(
            @TempDir Path root) throws Exception {
        writeCatalog(root, Map.of(
                "ci-cd.yml", "canonical verification",
                "database-compatibility.yml", "database matrix"));
        writeWorkflow(root, "ci-cd.yml", """
                name: CI
                jobs:
                  verify:
                    steps:
                      - run: mvn -Dtest=ExampleTest test
                """);
        writeWorkflow(root, "database-compatibility.yml", """
                name: Database
                jobs:
                  postgres:
                    steps:
                      - run: ./mvnw -B verify -Pdatabase-postgres
                """);

        WorkflowTestAuthorityPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.errors())
                .anyMatch(error -> error.contains("direct Maven executable"))
                .anyMatch(error -> error.contains("workflow-owned Java test selection"))
                .anyMatch(error -> error.contains(
                        "ci-cd.yml must invoke the canonical Maven command unchanged"))
                .anyMatch(error -> error.contains("database-mssql"))
                .anyMatch(error -> error.contains("database-oracle"));
    }

    @Test
    void reportsDirectBrowserAndPythonQualityExecution(
            @TempDir Path root) throws Exception {
        writeCatalog(root, Map.of(
                "ci-cd.yml", "canonical verification",
                "database-compatibility.yml", "database matrix",
                "delivery.yml", "artifact delivery only"));
        writeCanonicalWorkflows(root);
        writeWorkflow(root, "delivery.yml", """
                name: Delivery
                jobs:
                  publish:
                    steps:
                      - run: node .github/scripts/accessibility-audit.mjs
                      - run: python3 .github/scripts/check-doc-links.py
                """);

        WorkflowTestAuthorityPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.errors())
                .anyMatch(error -> error.contains("direct browser/a11y script"))
                .anyMatch(error -> error.contains("workflow-owned local quality test"));
    }

    @Test
    void reportsUnclassifiedMissingAndPreviouslyRemovedWorkflows(
            @TempDir Path root) throws Exception {
        writeCatalog(root, Map.of(
                "ci-cd.yml", "canonical verification",
                "database-compatibility.yml", "database matrix",
                "missing.yml", "documented but absent"));
        writeCanonicalWorkflows(root);
        writeWorkflow(root, "pipeline-tests.yml", """
                name: Redundant pipeline
                jobs:
                  verify:
                    steps:
                      - run: ./mvnw -B verify -Pci
                """);
        writeWorkflow(root, "unexpected.yaml", """
                name: Undocumented workflow
                jobs:
                  verify:
                    steps:
                      - run: ./mvnw -B verify -Pci
                """);

        WorkflowTestAuthorityPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.errors())
                .anyMatch(error -> error.contains("unclassified workflows remain")
                        && error.contains("pipeline-tests.yml")
                        && error.contains("unexpected.yaml"))
                .anyMatch(error -> error.contains("documented workflows missing: missing.yml"))
                .anyMatch(error -> error.contains(
                        "redundant workflows were not removed: pipeline-tests.yml"));
    }

    @Test
    void ignoresForbiddenTextOutsideRunScalarsAndAcceptsMultilineProfiles(
            @TempDir Path root) throws Exception {
        writeCatalog(root, Map.of(
                "ci-cd.yml", "canonical verification",
                "database-compatibility.yml", "database matrix"));
        writeWorkflow(root, "ci-cd.yml", """
                name: CI
                # Documentation example only: mvn -Dtest=DoNotExecute test
                env:
                  HISTORIC_EXAMPLE: "python3 .github/scripts/check-coverage.py"
                jobs:
                  verify:
                    steps:
                      - run: |
                          ./mvnw -B verify -Pci
                """);
        writeWorkflow(root, "database-compatibility.yml", """
                name: Database
                jobs:
                  matrix:
                    steps:
                      - run: >-
                          ./mvnw -B verify -Pdatabase-postgres
                      - run: |
                          ./mvnw -B verify -Pdatabase-mssql
                          ./mvnw -B verify -Pdatabase-oracle
                """);

        WorkflowTestAuthorityPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.errors()).isEmpty();
        assertThat(WorkflowTestAuthorityPolicy.runBlocks("""
                env:
                  EXAMPLE: mvn -Dtest=Ignored test
                steps:
                  - run: |
                      ./mvnw -B verify -Pci
                """))
                .isEqualTo("./mvnw -B verify -Pci");
    }

    private void writeCatalog(Path root, Map<String, String> responsibilities)
            throws IOException {
        Path catalog = root.resolve(".mvn/verification-suites.json");
        Files.createDirectories(catalog.getParent());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1);
        document.put("canonicalCommand", WorkflowTestAuthorityPolicy.CANONICAL_COMMAND);
        document.put("workflowResponsibilities", responsibilities);
        Files.writeString(catalog,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(document));
    }

    private static void writeCanonicalWorkflows(Path root) throws IOException {
        writeWorkflow(root, "ci-cd.yml", """
                name: CI
                jobs:
                  verify:
                    steps:
                      - run: ./mvnw -B verify -Pci
                """);
        writeWorkflow(root, "database-compatibility.yml", """
                name: Database
                jobs:
                  matrix:
                    steps:
                      - run: ./mvnw -B verify -Pdatabase-postgres
                      - run: ./mvnw -B verify -Pdatabase-mssql
                      - run: ./mvnw -B verify -Pdatabase-oracle
                """);
    }

    private static void writeWorkflow(Path root, String name, String content)
            throws IOException {
        Path workflow = root.resolve(".github/workflows").resolve(name);
        Files.createDirectories(workflow.getParent());
        Files.writeString(workflow, content);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(".mvn/verification-suites.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
