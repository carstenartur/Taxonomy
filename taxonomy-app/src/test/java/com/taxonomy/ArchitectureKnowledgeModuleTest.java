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
        Path repository = root;
        var forbiddenOwners = new com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>(
                "owned by the application, architecture, analysis or portfolio implementation module") {
            @Override public boolean test(com.tngtech.archunit.core.domain.JavaClass type) {
                return ArchitectureSourceOwnership.belongsTo(repository, type, "taxonomy-app", "taxonomy-architecture",
                        "taxonomy-analysis", "taxonomy-portfolio");
            }
        };
        var ownershipExamples = new ClassFileImporter().importClasses(com.taxonomy.shared.extension.ExtensionKind.class,
                com.taxonomy.shared.extension.runtime.ExtensionRegistry.class, com.taxonomy.analysis.service.LlmService.class);
        assertThat(forbiddenOwners.test(ownershipExamples.get(com.taxonomy.shared.extension.ExtensionKind.class))).isFalse();
        assertThat(forbiddenOwners.test(ownershipExamples.get(com.taxonomy.shared.extension.runtime.ExtensionRegistry.class))).isTrue();
        assertThat(forbiddenOwners.test(ownershipExamples.get(com.taxonomy.analysis.service.LlmService.class))).isTrue();
        noClasses().that().resideInAnyPackage("com.taxonomy.catalog..", "com.taxonomy.relations..", "com.taxonomy.search..")
                .should().dependOnClassesThat(forbiddenOwners).check(classes);
    }
}
