package com.taxonomy.portfolio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevents productive analysis paths from returning to globally scoped IDs. */
class AnalysisArtifactTenantRepositoryBoundaryTest {

    private static final List<String> MIGRATED_SERVICES = List.of(
            "PortfolioAnalysisPersistenceService.java",
            "PortfolioAnalysisRecoveryService.java",
            "PortfolioAnalysisWorkQueue.java",
            "ProjectRequirementAnalysisService.java");

    private static final List<String> FORBIDDEN_CALLS = List.of(
            "jobRepository.findById(",
            "jobRepository.findByIdAndProjectId(",
            "jobRepository.findByProjectIdAndIdempotencyKey(",
            "jobRepository.findByProjectIdOrderByCreatedAtDesc(",
            "itemRepository.findById(",
            "itemRepository.findByJobIdOrderByRequirementRequirementKeyAsc(",
            "itemRepository.findByJobIdAndStatusOrderByRequirementRequirementKeyAsc(",
            "itemRepository.findByJobIdAndStatusAndStartedAtBeforeOrderByRequirementRequirementKeyAsc(",
            "itemRepository.findByJobIdAndRequirementId(",
            "snapshotRepository.findById(",
            "snapshotRepository.findByIdAndProjectId(",
            "snapshotRepository.findByRequirementIdOrderByCreatedAtDesc(",
            "snapshotRepository.findByProjectIdOrderByCreatedAtDesc(",
            "elementRepository.findBySnapshotIdOrderByTaxonomyRootAscNodeCodeAsc(",
            "elementRepository.findByIdAndSnapshotProjectId(",
            "relationRepository.findBySnapshotIdOrderBySourceCodeAscTargetCodeAsc(",
            "relationRepository.findByIdAndSnapshotProjectId(",
            "hypothesisRepository.findByAnalysisSessionId(");

    @Test
    void migratedAnalysisServicesUseOnlyExactTenantRepositoryMethods() throws IOException {
        Path serviceDirectory = repositoryRoot().resolve(
                "taxonomy-app/src/main/java/com/taxonomy/portfolio/service");
        for (String service : MIGRATED_SERVICES) {
            String source = Files.readString(
                    serviceDirectory.resolve(service), StandardCharsets.UTF_8);
            for (String forbidden : FORBIDDEN_CALLS) {
                assertThat(source)
                        .as("%s must not use %s", service, forbidden)
                        .doesNotContain(forbidden);
            }
        }
    }

    @Test
    void migrationContainsCompleteAnalysisTenantForeignKeys() throws IOException {
        String migration = Files.readString(repositoryRoot().resolve(
                        "taxonomy-app/src/main/resources/db/migration/taxonomy/postgresql/"
                                + "V14__scope_analysis_artifacts_by_tenant.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("foreign key (job_id, project_id, scope_key)")
                .contains("foreign key (requirement_id, project_id, scope_key)")
                .contains("foreign key (requirement_version_id, requirement_id, scope_key)")
                .contains("foreign key (snapshot_id, job_id, requirement_id,")
                .contains("foreign key (current_snapshot_id, id, project_id, scope_key)")
                .contains("foreign key (snapshot_id, scope_key)");
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("taxonomy-app"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
