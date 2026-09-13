package com.taxonomy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureWorkspaceAuthorityBoundaryTest {
    private static final String BOOTSTRAP_OWNER =
            "com.taxonomy.composition.dsl.service.GitRepositoryBootstrap";
    private static final String FORMER_BOOTSTRAP_OWNER =
            "com.taxonomy.versioning.service.GitRepositoryBootstrap";

    @Test
    void bootstrapOrchestrationBelongsToComposition() {
        JavaClasses classes = productionClasses();

        assertThat(classes.contain(BOOTSTRAP_OWNER)).isTrue();
        assertThat(classes.contain(FORMER_BOOTSTRAP_OWNER)).isFalse();
    }

    @Test
    void workspaceAuthorityDoesNotDependOnApplicationOrKnowledgeImplementations() {
        JavaClasses classes = productionClasses();

        assertThat(classes.contain("com.taxonomy.workspace.service.WorkspaceContext")).isTrue();
        assertThat(classes.contain("com.taxonomy.versioning.service.DslOperationsFacade")).isTrue();
        assertThat(classes.contain("com.taxonomy.editor.ArchitectureEditorService")).isTrue();

        noClasses().that().resideInAnyPackage(
                        "com.taxonomy.workspace..",
                        "com.taxonomy.versioning..",
                        "com.taxonomy.editor..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.taxonomy.composition..",
                        "com.taxonomy.shared..",
                        "com.taxonomy.security..",
                        "com.taxonomy.observability..",
                        "com.taxonomy.catalog..",
                        "com.taxonomy.relations..",
                        "com.taxonomy.search..",
                        "com.taxonomy.architecture..",
                        "com.taxonomy.portfolio..",
                        "com.taxonomy.dsl.export..")
                .because("workspace owns repository/version/editor authority while application "
                        + "and knowledge composition stay above it")
                .allowEmptyShould(false)
                .check(classes);
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy");
    }
}
