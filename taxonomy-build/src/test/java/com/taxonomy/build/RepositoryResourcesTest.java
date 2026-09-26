package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryResourcesTest {
    @TempDir
    Path temporary;

    @Test
    void readsTheSameUtf8SourceFromRootModuleAndNestedWorkingDirectories() throws Exception {
        Path repository = repository();
        Path resources = repository.resolve("taxonomy-app/src/main/resources");
        Files.createDirectories(resources.resolve("templates"));
        Files.writeString(resources.resolve("templates/example.html"), "Prüfung – vollständig", StandardCharsets.UTF_8);

        for (Path working : new Path[] {repository, repository.resolve("taxonomy-build"),
                repository.resolve("taxonomy-build/target/test-work")}) {
            Files.createDirectories(working);
            assertThat(RepositoryResources.applicationResource(working, "/templates/example.html"))
                    .isEqualTo("Prüfung – vollständig");
            assertThat(RepositoryResources.applicationResource(working, "templates/example.html"))
                    .isEqualTo("Prüfung – vollständig");
        }
    }

    @Test
    void doesNotSubstituteAnOldPackagedCopyForAMissingSource() throws Exception {
        Path repository = repository();
        Path oldCopy = repository.resolve("taxonomy-app/target/classes/templates/missing.html");
        Files.createDirectories(oldCopy.getParent());
        Files.writeString(oldCopy, "stale build output");

        assertThatThrownBy(() -> RepositoryResources.applicationResource(repository, "templates/missing.html"))
                .isInstanceOf(NoSuchFileException.class)
                .hasMessageContaining("src/main/resources");
    }

    @Test
    void refusesPathsOutsideTheApplicationResources() throws Exception {
        Path repository = repository();
        for (String path : new String[] {"../pom.xml", "/../../pom.xml", "//tmp/example", ""}) {
            assertThatThrownBy(() -> RepositoryResources.applicationResource(repository, path))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void refusesAnUnrelatedWorkingDirectory() throws Exception {
        Path unrelated = Files.createDirectories(temporary.resolve("unrelated"));
        assertThatThrownBy(() -> RepositoryResources.applicationResource(unrelated, "application.properties"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Taxonomy repository");
    }

    private Path repository() throws Exception {
        Path repository = Files.createDirectories(temporary.resolve("checkout"));
        Files.createDirectories(repository.resolve(".mvn"));
        Files.writeString(repository.resolve(".mvn/verification-suites.json"), "{}");
        Files.createDirectories(repository.resolve("taxonomy-app/src/main/resources"));
        return repository;
    }
}
