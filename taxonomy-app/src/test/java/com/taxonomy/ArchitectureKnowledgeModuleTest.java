package com.taxonomy;

import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.relations.service.RelationTraversalService;
import com.taxonomy.search.EmbeddingBridgeSupport;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** The catalogue, relations and search must be shipped by one independent library. */
class ArchitectureKnowledgeModuleTest {
    @Test
    void knowledgeIsOwnedByItsLibraryWithoutApplicationImplementations() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/architecture-contexts.json"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository root").isNotNull();
        Path module = root.resolve("taxonomy-knowledge");
        assertThat(module.resolve("pom.xml")).as("physical knowledge Maven module").isRegularFile();
        for (String part : List.of("catalog", "relations", "search")) {
            assertThat(root.resolve("taxonomy-app/src/main/java/com/taxonomy/" + part)).doesNotExist();
        }
        for (Class<?> implementation : List.of(TaxonomyService.class,
                RelationTraversalService.class, EmbeddingBridgeSupport.class)) {
            String relative = implementation.getName().replace('.', '/');
            assertThat(module.resolve("src/main/java/" + relative + ".java")).isRegularFile();
            assertThat(module.resolve("target/classes/" + relative + ".class")).isRegularFile();
            assertThat(implementation.getProtectionDomain().getCodeSource().getLocation().toString())
                    .as("runtime owner of %s", implementation.getName()).contains("taxonomy-knowledge");
        }
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy.catalog", "com.taxonomy.relations", "com.taxonomy.search");
        noClasses().that().resideInAnyPackage("com.taxonomy.catalog..", "com.taxonomy.relations..",
                        "com.taxonomy.search..")
                .should().dependOnClassesThat().resideInAnyPackage("com.taxonomy.shared..",
                        "com.taxonomy.composition..", "com.taxonomy.architecture..",
                        "com.taxonomy.analysis..", "com.taxonomy.portfolio..", "com.taxonomy.dsl.export..")
                .check(classes);
    }
}
