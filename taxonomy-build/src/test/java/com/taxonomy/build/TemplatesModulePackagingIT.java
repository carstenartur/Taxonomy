package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TemplatesModulePackagingIT {
    @Test
    void applicationContainsOneTemplateLibraryWithItsCodeAndDefaultResource() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".mvn/verification-suites.json"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        List<Path> applications;
        try (var files = Files.list(root.resolve("taxonomy-app/target"))) {
            applications = files.filter(p -> p.getFileName().toString().startsWith("taxonomy-app-")
                    && p.getFileName().toString().endsWith(".jar")).toList();
        }
        assertThat(applications).hasSize(1);
        try (var jar = new ZipFile(applications.getFirst().toFile())) {
            var names = jar.stream().map(entry -> entry.getName()).toList();
            var libraries = names.stream().filter(n -> n.startsWith("BOOT-INF/lib/taxonomy-templates-")
                    && n.endsWith(".jar")).toList();
            assertThat(libraries).hasSize(1);
            assertThat(names).noneMatch(n -> n.startsWith("BOOT-INF/classes/com/taxonomy/templates/"));
            assertThat(names).doesNotContain("BOOT-INF/classes/document-templates/decision-rationale-report.dotx");
            assertThat(names).anyMatch(n -> n.startsWith("BOOT-INF/classes/db/migration/"));
            var entries = new HashSet<String>();
            try (var library = new ZipInputStream(jar.getInputStream(jar.getEntry(libraries.getFirst())))) {
                for (var entry = library.getNextEntry(); entry != null; entry = library.getNextEntry()) {
                    entries.add(entry.getName());
                }
            }
            assertThat(entries).contains("document-templates/decision-rationale-report.dotx",
                    "com/taxonomy/templates/DocumentTemplateGitRepository.class",
                    "com/taxonomy/templates/OoxmlTemplatePackageCodec.class",
                    "com/taxonomy/templates/DocumentTemplateWebDavServlet.class",
                    "com/taxonomy/templates/DefaultDocumentTemplateBootstrap.class");
        }
    }
}
