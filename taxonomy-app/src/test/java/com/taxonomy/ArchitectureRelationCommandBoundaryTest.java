package com.taxonomy;

import com.taxonomy.workspace.service.WorkspaceDslVersionPort;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureRelationCommandBoundaryTest {

    @Test
    void relationCommandsUseTheWorkspacePortInsteadOfStorageImplementations() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy.relations.command");

        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("com.taxonomy.dsl.storage..", "org.eclipse.jgit..")
                .because("relation semantics belong to knowledge, exact Git version authority to workspace")
                .check(classes);
    }

    @Test
    void workspaceVersionContractExposesNoStorageFrameworkOrKnowledgeTypes() {
        var classes = new ClassFileImporter().importClasses(
                WorkspaceDslVersionPort.class,
                WorkspaceDslVersionPort.ExactVersion.class,
                WorkspaceDslVersionPort.CommitResult.class);

        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("com.taxonomy.dsl.storage..", "org.eclipse.jgit..",
                        "org.springframework..", "com.taxonomy.relations..", "com.taxonomy.catalog..")
                .because("the workspace version port is an owner-defined API, not an implementation facade")
                .check(classes);
    }
}
