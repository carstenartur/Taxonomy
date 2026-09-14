package com.taxonomy;

import com.taxonomy.templates.DefaultDocumentTemplateBootstrap;
import com.taxonomy.templates.DocumentTemplateGitRepository;
import com.taxonomy.templates.DocumentTemplateWebDavServlet;
import com.taxonomy.templates.OoxmlTemplatePackageCodec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Enforces source and runtime ownership of the template library. */
class ArchitectureTemplatesModuleTest {

    @Test
    void templateImplementationsAndDefaultTemplateBelongToTheLibrary() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/architecture-contexts.json"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository root").isNotNull();
        Path module = root.resolve("taxonomy-templates");
        assertThat(module.resolve("pom.xml")).as("physical templates Maven module").isRegularFile();
        assertThat(root.resolve("taxonomy-app/src/main/java/com/taxonomy/templates")).doesNotExist();
        for (Class<?> implementation : List.of(DocumentTemplateGitRepository.class,
                OoxmlTemplatePackageCodec.class, DocumentTemplateWebDavServlet.class,
                DefaultDocumentTemplateBootstrap.class)) {
            String relative = implementation.getName().replace('.', '/');
            assertThat(module.resolve("src/main/java/" + relative + ".java")).isRegularFile();
            assertThat(module.resolve("target/classes/" + relative + ".class")).isRegularFile();
            assertThat(implementation.getProtectionDomain().getCodeSource().getLocation().toString())
                    .as("runtime owner of %s", implementation.getName()).contains("taxonomy-templates");
        }
        String resource = "document-templates/decision-rationale-report.dotx";
        assertThat(module.resolve("src/main/resources/" + resource)).isRegularFile();
        assertThat(root.resolve("taxonomy-app/src/main/resources/" + resource)).doesNotExist();
        var resources = java.util.Collections.list(getClass().getClassLoader().getResources(resource));
        assertThat(resources).as("one classpath owner for the bundled template").hasSize(1);
        assertThat(resources.getFirst().toString()).contains("taxonomy-templates");
    }
}
