package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseParameterAncestryContractTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsAnAlreadyAdvancedDevelopmentTreeWhoseReleaseTagDiverged()
            throws Exception {
        Path repository = createReleaseRepository(false);
        var parameters = new ReleaseParametersResolver.Parameters(
                "1.3.1", "1.3.2-SNAPSHOT", "false", "false", "false");

        assertThatThrownBy(() -> ReleaseParametersResolver
                .validateStagedReleaseAncestry(
                        repository, parameters, "1.3.2-SNAPSHOT"))
                .hasMessageContaining("staged release tag v1.3.1 is not an ancestor")
                .hasMessageContaining("repair release ancestry before publication");
    }

    @Test
    void acceptsTheSameDevelopmentTreeAfterAHistoryOnlyAncestryMerge()
            throws Exception {
        Path repository = createReleaseRepository(true);
        var parameters = new ReleaseParametersResolver.Parameters(
                "1.3.1", "1.3.2-SNAPSHOT", "false", "false", "false");

        ReleaseParametersResolver.validateStagedReleaseAncestry(
                repository, parameters, "1.3.2-SNAPSHOT");
    }

    private Path createReleaseRepository(boolean repairAncestry)
            throws Exception {
        Path repository = temporaryDirectory.resolve("repository");
        Files.createDirectories(repository);
        TestGit.run(repository, "init");
        TestGit.run(repository, "symbolic-ref", "HEAD", "refs/heads/main");
        TestGit.run(repository, "config", "user.name", "Release Contract");
        TestGit.run(repository, "config", "user.email",
                "release-contract@taxonomy.local");

        writePom(repository, "1.3.1-SNAPSHOT");
        commitPom(repository, "Development base");
        TestGit.run(repository, "checkout", "-b", "release/1.3.1");
        writePom(repository, "1.3.1");
        commitPom(repository, "Release version 1.3.1");
        TestGit.run(repository, "tag", "v1.3.1");

        TestGit.run(repository, "checkout", "main");
        writePom(repository, "1.3.2-SNAPSHOT");
        commitPom(repository, "Prepare next development version 1.3.2-SNAPSHOT");
        if (repairAncestry) {
            TestGit.run(repository, "merge", "-s", "ours", "--no-ff",
                    "release/1.3.1", "-m", "Repair v1.3.1 ancestry");
        }
        return repository;
    }

    private static void writePom(Path repository, String version)
            throws Exception {
        Files.writeString(repository.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>release-contract</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(version), StandardCharsets.UTF_8);
    }

    private static void commitPom(Path repository, String message)
            throws Exception {
        TestGit.run(repository, "add", "pom.xml");
        TestGit.run(repository, "commit", "-m", message);
    }
}
