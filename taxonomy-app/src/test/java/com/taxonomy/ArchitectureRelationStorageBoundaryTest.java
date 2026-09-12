package com.taxonomy;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureRelationStorageBoundaryTest {
    @Test
    void relationServicesAndHttpAdaptersHaveNoStorageOrJGitDependencies() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy.relations.command", "com.taxonomy.relations.service",
                        "com.taxonomy.relations.controller");
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("com.taxonomy.dsl.storage..", "org.eclipse.jgit..")
                .because("knowledge owns relations while workspace owns version authority and its conflicts")
                .check(classes);
    }

    @Test
    void thePublicConflictContractIsFrameworkAndStorageIndependent() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("com.taxonomy.dsl.storage..", "org.eclipse.jgit..", "org.springframework..",
                        "com.taxonomy.relations..")
                .check(new ClassFileImporter().importClasses(BranchHeadConflictException.class));
    }
}
