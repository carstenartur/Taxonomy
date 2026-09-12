package com.taxonomy;

import com.taxonomy.versioning.controller.DslApiController;
import com.taxonomy.relations.controller.GitHypothesisReviewCompatibilityFilter;
import com.taxonomy.relations.controller.HypothesisHeadApiController;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureHypothesisHttpBoundaryTest {

    @Test
    void hypothesisHttpAdaptersBelongToRelations() {
        var adapters = new ClassFileImporter().importClasses(
                GitHypothesisReviewCompatibilityFilter.class, HypothesisHeadApiController.class);

        classes().should().resideInAPackage("com.taxonomy.relations.controller..")
                .because("the historical DSL URL does not make hypothesis review a versioning responsibility")
                .check(adapters);
    }

    @Test
    void dslVersionControllerDoesNotImplementHypothesisWorkflows() {
        var controller = new ClassFileImporter().importClasses(DslApiController.class);

        noClasses().should().dependOnClassesThat().resideInAnyPackage("com.taxonomy.relations..")
                .because("hypothesis HTTP workflows have a relation-owned controller")
                .check(controller);
    }
}
