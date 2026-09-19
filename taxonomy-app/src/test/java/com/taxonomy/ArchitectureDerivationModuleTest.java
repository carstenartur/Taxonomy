package com.taxonomy;

import com.taxonomy.architecture.service.ArchitectureReportService;
import com.taxonomy.architecture.pipeline.ArchitectureViewPipeline;
import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureDerivationModuleTest {
    @Test
    void architectureIsOwnedByAnIndependentLibrary() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/architecture-contexts.json"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository root").isNotNull();
        Path module = root.resolve("taxonomy-architecture");
        assertThat(module.resolve("pom.xml")).as("physical architecture Maven module").isRegularFile();
        assertThat(root.resolve("taxonomy-app/src/main/java/com/taxonomy/architecture")).doesNotExist();
        for (Class<?> implementation : List.of(ArchitectureReportService.class,
                ArchitectureViewPipeline.class, DecisionRationaleReportService.class)) {
            String relative = implementation.getName().replace('.', '/');
            assertThat(module.resolve("src/main/java/" + relative + ".java")).isRegularFile();
            assertThat(module.resolve("target/classes/" + relative + ".class")).isRegularFile();
            assertThat(implementation.getProtectionDomain().getCodeSource().getLocation().toString())
                    .as("runtime owner of %s", implementation.getName()).contains("taxonomy-architecture");
        }
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy.architecture");
        assertThat(root.resolve("taxonomy-app/src/main/java/com/taxonomy/composition/report/ReportApiController.java"))
                .as("workspace-aware report HTTP composition").isRegularFile();
        for (var type : classes) {
            for (var dependency : type.getDirectDependenciesFromSelf()) {
                assertThat(dependency.getTargetClass().getName())
                        .as("architecture must not resolve implicit workspaces: %s", dependency)
                        .isNotEqualTo("com.taxonomy.workspace.service.WorkspaceResolver");
            }
        }
        noClasses().that().resideInAPackage("com.taxonomy.architecture..")
                .should().dependOnClassesThat().resideInAnyPackage("com.taxonomy.composition..",
                        "com.taxonomy.shared.config..", "com.taxonomy.shared.service..",
                        "com.taxonomy.preferences..", "com.taxonomy.analysis..", "com.taxonomy.portfolio..",
                        "com.taxonomy.dsl.export..")
                .check(classes);
    }
}
