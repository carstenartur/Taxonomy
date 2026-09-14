package com.taxonomy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** Physical library ownership and a non-vacuous check of the extracted boundary. */
class ArchitectureInteropModuleTest {
    @Test
    void interoperabilityImplementationBelongsToItsLibrary() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/architecture-contexts.json"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository root").isNotNull();
        Path module = root.resolve("taxonomy-interop");
        assertThat(module.resolve("pom.xml")).as("physical interoperability Maven module").isRegularFile();
        assertThat(root.resolve("taxonomy-app/src/main/java/com/taxonomy/interop")).doesNotExist();
        for (String name : List.of("com.taxonomy.interop.IntegrationService",
                "com.taxonomy.interop.persistence.IntegrationStore",
                "com.taxonomy.interop.oslc.OslcProviderService")) {
            Class<?> type = Class.forName(name);
            String relative = name.replace('.', '/');
            assertThat(module.resolve("src/main/java/" + relative + ".java")).isRegularFile();
            assertThat(module.resolve("target/classes/" + relative + ".class")).isRegularFile();
            assertThat(type.getProtectionDomain().getCodeSource().getLocation().toString())
                    .as("runtime owner of %s", name).contains("taxonomy-interop");
        }
    }

    @Test
    void interoperabilityUsesPortsInsteadOfApplicationImplementations() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy.interop");
        assertThat(classes.get("com.taxonomy.interop.IntegrationService")).isNotNull();
        assertThat(classes.get("com.taxonomy.interop.oslc.OslcProviderService")).isNotNull();
        noClasses().that().resideInAPackage("com.taxonomy.interop..")
                .should().dependOnClassesThat().resideInAnyPackage("com.taxonomy.portfolio..",
                        "com.taxonomy.composition..", "com.taxonomy.editor..", "com.taxonomy.shared..")
                .because("the interoperability library must not require the application or editor implementation")
                .check(classes);
    }
}
