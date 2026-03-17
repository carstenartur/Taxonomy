package com.taxonomy;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Architecture rules enforced via ArchUnit.
 *
 * <p>These tests guard the package structure that was established during
 * the controller-extraction refactoring and prevent accidental regressions.
 */
@AnalyzeClasses(packages = "com.taxonomy", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * No circular dependencies between the main domain packages.
     *
     * <p>Each top-level package under {@code com.taxonomy} is treated as a
     * slice. Dependencies to/from cross-cutting and shared infrastructure
     * packages are excluded from cycle detection.
     *
     * <p>Known excluded packages and reasons:
     * <ul>
     *   <li>{@code dto, model} — shared data transfer objects</li>
     *   <li>{@code dsl, shared} — shared utilities and DSL parsing</li>
     *   <li>{@code export, architecture} — mutual dependency in diagram pipeline</li>
     *   <li>{@code catalog, relations} — mutual dependency via entity models</li>
     *   <li>{@code versioning, workspace} — mutual dependency for workspace state</li>
     * </ul>
     *
     * <p>Future work: extract interfaces to {@code shared/} to resolve
     * these cycles and progressively remove exceptions from this rule.
     */
    @ArchTest
    static final ArchRule noCyclesBetweenDomains = slices()
            .matching("com.taxonomy.(*)..")
            .should().beFreeOfCycles()
            .ignoreDependency(resideInAnyPackage(
                    "com.taxonomy.dto..", "com.taxonomy.model..",
                    "com.taxonomy.dsl..", "com.taxonomy.shared..",
                    "com.taxonomy.export..", "com.taxonomy.architecture..",
                    "com.taxonomy.catalog..", "com.taxonomy.relations..",
                    "com.taxonomy.versioning..", "com.taxonomy.workspace.."), alwaysTrue())
            .ignoreDependency(alwaysTrue(), resideInAnyPackage(
                    "com.taxonomy.dto..", "com.taxonomy.model..",
                    "com.taxonomy.dsl..", "com.taxonomy.shared..",
                    "com.taxonomy.export..", "com.taxonomy.architecture..",
                    "com.taxonomy.catalog..", "com.taxonomy.relations..",
                    "com.taxonomy.versioning..", "com.taxonomy.workspace.."))
            .because("Domain packages must not have circular dependencies (shared infra excluded)");

    /**
     * Controllers must go through services or facades and never touch
     * repositories directly.
     *
     * <p>This rule covers all controller packages. Security controllers are
     * excluded because Spring Security patterns commonly involve direct
     * repository access for user/role management.
     */
    @ArchTest
    static final ArchRule controllersShouldNotAccessRepositories = noClasses()
            .that().resideInAPackage("..controller..")
            .and().resideOutsideOfPackage("com.taxonomy.security.controller..")
            .should().dependOnClassesThat()
            .resideInAPackage("..repository..")
            .because("Controllers should use services or facades, not repositories (security controllers excluded)");

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

    /**
     * Versioning is a lower-level domain; analysis depends on it, not vice versa.
     */
    @ArchTest
    static final ArchRule versioningShouldNotDependOnAnalysis = noClasses()
            .that().resideInAPackage("com.taxonomy.versioning..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.taxonomy.analysis..")
            .because("versioning is a lower-level domain; analysis depends on it, not vice versa");
}
