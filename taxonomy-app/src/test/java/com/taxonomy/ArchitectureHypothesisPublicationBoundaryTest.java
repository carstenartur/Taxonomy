package com.taxonomy;

import com.taxonomy.relations.service.GitAuthoritativeHypothesisService;
import com.taxonomy.relations.service.GitAuthoritativeHypothesisReviewService;
import com.taxonomy.relations.service.HypothesisService;
import com.taxonomy.relations.service.HypothesisReviewStateStore;
import com.taxonomy.workspace.service.WorkspaceDslPublicationPort;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class ArchitectureHypothesisPublicationBoundaryTest {

    @Test
    void hypothesisAuthorityBelongsToRelations() {
        var hypothesisClasses = new ClassFileImporter().importClasses(
                HypothesisService.class, GitAuthoritativeHypothesisService.class,
                GitAuthoritativeHypothesisReviewService.class, HypothesisReviewStateStore.class);

        classes().should().resideInAPackage("com.taxonomy.relations.service..")
                .because("hypothesis lifecycle and review belong to knowledge, not workspace versioning")
                .check(hypothesisClasses);
    }

    @Test
    void hypothesisServicesResolveContextThroughWorkspaceApi() {
        var hypothesisClasses = new ClassFileImporter().importClasses(
                HypothesisService.class, GitAuthoritativeHypothesisService.class,
                GitAuthoritativeHypothesisReviewService.class, HypothesisReviewStateStore.class);

        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("com.taxonomy.workspace.repository..", "com.taxonomy.workspace.model..")
                .because("repository selection and workspace provenance validation belong to workspace")
                .check(hypothesisClasses);
    }

    @Test
    void hypothesisLifecycleAndCompatibilityDoNotDependOnGitStorage() {
        var classes = new ClassFileImporter().importClasses(
                HypothesisService.class, GitAuthoritativeHypothesisService.class,
                GitAuthoritativeHypothesisReviewService.class, HypothesisReviewStateStore.class);

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
