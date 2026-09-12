package com.taxonomy;

import com.taxonomy.versioning.service.GitAuthoritativeHypothesisService;
import com.taxonomy.versioning.service.HypothesisService;
import com.taxonomy.workspace.service.WorkspaceDslPublicationPort;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureHypothesisPublicationBoundaryTest {

    @Test
    void hypothesisLifecycleAndCompatibilityDoNotDependOnGitStorage() {
        var classes = new ClassFileImporter().importClasses(
                HypothesisService.class, GitAuthoritativeHypothesisService.class);

        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("com.taxonomy.dsl.storage..", "org.eclipse.jgit..")
                .because("hypothesis semantics must be movable to knowledge without reintroducing storage coupling")
                .check(classes);
    }

    @Test
    void snapshotPublicationContractDoesNotExposeItsStorageOrHypothesisConsumers() {
        var classes = new ClassFileImporter().importClasses(WorkspaceDslPublicationPort.class);

        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("com.taxonomy.dsl.storage..", "org.eclipse.jgit..",
                        "org.springframework..", "com.taxonomy.relations..",
                        "com.taxonomy.versioning..", "com.taxonomy.catalog..")
                .because("workspace owns publication mechanics, not hypothesis lifecycle or transactions")
                .check(classes);
    }
}
