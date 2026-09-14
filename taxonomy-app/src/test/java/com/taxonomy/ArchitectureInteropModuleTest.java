package com.taxonomy;

import com.taxonomy.interop.IntegrationDomainAdapter;
import com.taxonomy.interop.IntegrationService;
import com.taxonomy.interop.oslc.OslcProviderService;
import com.taxonomy.interop.persistence.IntegrationStore;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Physical ownership and the deliberately narrow dependencies of interoperability. */
class ArchitectureInteropModuleTest {
    @Test
    void interoperabilityIsOwnedByItsLibraryWithoutApplicationOrPortfolioImplementations() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/architecture-contexts.json"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository root").isNotNull();
        Path module = root.resolve("taxonomy-interop");
        assertThat(module.resolve("pom.xml")).as("physical interop Maven module").isRegularFile();
        assertThat(root.resolve("taxonomy-app/src/main/java/com/taxonomy/interop")).doesNotExist();
        for (Class<?> implementation : List.of(IntegrationService.class, IntegrationDomainAdapter.class,
                IntegrationStore.class, OslcProviderService.class)) {
            String relative = implementation.getName().replace('.', '/');
            assertThat(module.resolve("src/main/java/" + relative + ".java")).isRegularFile();
            assertThat(module.resolve("target/classes/" + relative + ".class")).isRegularFile();
            assertThat(implementation.getProtectionDomain().getCodeSource().getLocation().toString())
                    .as("runtime owner of %s", implementation.getName()).contains("taxonomy-interop");
        }
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy.interop");
        noClasses().that().resideInAPackage("com.taxonomy.interop..")
                .should().dependOnClassesThat().resideInAnyPackage("com.taxonomy.portfolio..",
                        "com.taxonomy.composition..", "com.taxonomy.editor..")
                .check(classes);
    }
}
