package com.taxonomy.portfolio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevents migrated productive requirement paths from returning to ID-only lookups. */
class RequirementTenantRepositoryBoundaryTest {

    private static final List<String> MIGRATED_SERVICES = List.of(
            "ProjectPortfolioService.java",
            "ProjectConflictService.java",
            "PortfolioGitService.java",
            "PortablePortfolioGitService.java");

    private static final List<String> FORBIDDEN_CALLS = List.of(
            "requirementRepository.findByProjectIdOrderByRequirementKeyAsc(",
            "requirementRepository.findByIdAndProjectId(",
            "requirementRepository.findByIdAndProjectIdForUpdate(",
            "requirementRepository.findByProjectIdAndRequirementKeyIgnoreCase(",
            "requirementRepository.countByProjectId(",
            "versionRepository.findByRequirementIdOrderByVersionNumberDesc(",
            "versionRepository.findFirstByRequirementIdOrderByVersionNumberDesc(",
            "versionRepository.findByRequirementIdAndVersionNumber(",
            "versionRepository.findByRequirementIdAndContentHash(",
            "versionRepository.findByIdAndRequirementId(");

    @Test
    void migratedServicesUseOnlyExactTenantRequirementRepositories() throws IOException {
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
