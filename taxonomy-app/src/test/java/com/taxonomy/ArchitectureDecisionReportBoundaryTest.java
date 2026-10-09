package com.taxonomy;

import com.taxonomy.reporting.render.decision.DecisionRationaleJsonRenderer;
import com.taxonomy.reporting.render.decision.DecisionRationaleHtmlRenderer;
import com.taxonomy.reporting.render.decision.DecisionRationaleDocxRenderer;
import com.taxonomy.reporting.render.document.MarkdownReportRendererExtension;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionReportScope;
import com.taxonomy.reporting.api.document.ArchitectureReportDocument;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureDecisionReportBoundaryTest {

    @Test
    void renderersHaveAnIndependentPhysicalOwner() {
        for (Class<?> renderer : java.util.List.of(
                com.taxonomy.reporting.render.decision.DecisionRationaleHtmlRenderer.class,
                com.taxonomy.reporting.render.decision.DecisionRationaleJsonRenderer.class,
                com.taxonomy.reporting.render.decision.DecisionRationaleDocxRenderer.class,
                com.taxonomy.reporting.render.document.MarkdownReportRendererExtension.class)) {
            org.assertj.core.api.Assertions.assertThat(renderer.getProtectionDomain().getCodeSource()
                    .getLocation().toString()).as("physical renderer owner: %s", renderer.getName())
                    .contains("taxonomy-reporting/");
        }
        var reporting = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy.reporting.render", "com.taxonomy.reporting.templates");
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "com.taxonomy.architecture..", "com.taxonomy.catalog..", "com.taxonomy.workspace..")
                .because("rendering consumes captured data and template ports, never live derivation")
                .allowEmptyShould(false).check(reporting);
    }

    @Test
    void reportModelsArePublishedWithoutArchitectureImplementationDependencies() {
        for (String type : java.util.List.of(
                "com.taxonomy.reporting.api.decision.DecisionRationaleReport",
                "com.taxonomy.reporting.api.decision.DecisionReportScope",
                "com.taxonomy.reporting.api.document.ArchitectureReportDocument")) {
            Class<?> model = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> Class.forName(type), "Independently built report renderers need a supported data API");
            org.assertj.core.api.Assertions.assertThat(model.getProtectionDomain().getCodeSource()
                    .getLocation().toString()).contains("taxonomy-reporting-api");
        }
        var contracts = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy.reporting.api");
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "com.taxonomy.architecture..", "com.taxonomy.templates..",
                        "com.taxonomy.reporting.render..", "com.taxonomy.catalog..",
                        "org.springframework..", "jakarta.persistence..")
                .because("a renderer receives frozen report data, never a live implementation")
                .allowEmptyShould(false).check(contracts);
    }

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
