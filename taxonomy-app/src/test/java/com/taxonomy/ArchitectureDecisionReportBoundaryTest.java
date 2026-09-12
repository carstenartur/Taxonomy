package com.taxonomy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureDecisionReportBoundaryTest {

    @Test
    void decisionReportOrchestrationBelongsToCompositionInsteadOfVersioning() {
        var imported = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy.versioning.controller", "com.taxonomy.composition.report");

        noClasses().that().resideInAPackage("com.taxonomy.versioning.controller..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.taxonomy.architecture.decision..",
                        "com.taxonomy.architecture.report..", "com.taxonomy.catalog.service..")
                .because("versioning HTTP adapters must not own architecture/knowledge report orchestration")
                .allowEmptyShould(false)
                .check(imported);

        classes().that().haveSimpleName("DecisionRationaleReportController")
                .should().resideInAPackage("com.taxonomy.composition.report")
                .because("decision reports combine architecture rendering, knowledge scores and workspace provenance")
                .allowEmptyShould(false)
                .check(imported);
    }
}
