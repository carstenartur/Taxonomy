package com.taxonomy.editor;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.assertThat;

class EditorTestFixtureArchiveTest {
    @Test void onlyTheSharedFixtureIsPublishedNotTestsOrApplicationConfiguration() throws Exception {
        Path testClasses = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Path> archives;
        try (var files = Files.list(testClasses.getParent())) {
            archives = files.filter(p -> p.getFileName().toString().endsWith("-test-fixtures.jar")).toList();
        }
        assertThat(archives).hasSize(1);
        try (var archive = new ZipFile(archives.getFirst().toFile())) {
            var content = archive.stream().filter(e -> !e.isDirectory())
                    .map(e -> e.getName()).filter(n -> !n.startsWith("META-INF/")).toList();
            assertThat(content).containsExactly("com/taxonomy/editor/EditorPersistenceFixture.class");
        }
    }
}
