package com.taxonomy;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture rules enforced via ArchUnit.
 *
 * <p>These tests guard the package structure that was established during
 * the controller-extraction refactoring and prevent accidental regressions.
 */
@AnalyzeClasses(packages = "com.taxonomy", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * Controllers in the newly-extracted packages (analysis, export, search, catalog,
     * shared) must go through services / facades and never touch repositories.
     *
     * <p>Some pre-existing controllers (architecture, security, versioning) still
     * reference repositories directly; those are intentionally excluded so the
     * rule passes today and protects future code.
     */
    @ArchTest
    static final ArchRule newControllersShouldNotAccessRepositories = noClasses()
            .that().resideInAnyPackage(
                    "com.taxonomy.analysis.controller..",
                    "com.taxonomy.export.controller..",
                    "com.taxonomy.search.controller..",
                    "com.taxonomy.catalog.controller..",
                    "com.taxonomy.shared.controller..")
            .should().dependOnClassesThat()
            .resideInAPackage("..repository..")
            .because("Newly-extracted controllers should use services or facades, not repositories directly");

    @ArchTest
    static final ArchRule servicesShouldNotDependOnControllers = noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat()
            .resideInAPackage("..controller..")
            .because("Services must not depend on controllers — this would invert the dependency direction");

    @ArchTest
    static final ArchRule controllerNamesShouldEndWithController = classes()
            .that().resideInAPackage("..controller..")
            .and().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().haveSimpleNameEndingWith("Controller")
            .because("REST controllers should follow the naming convention *Controller");

    @ArchTest
    static final ArchRule facadesShouldNotDependOnControllers = noClasses()
            .that().haveSimpleNameEndingWith("Facade")
            .should().dependOnClassesThat()
            .resideInAPackage("..controller..")
            .because("Facades aggregate services; they must not depend on controllers");
}
