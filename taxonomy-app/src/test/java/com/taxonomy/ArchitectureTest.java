package com.taxonomy;

import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
 *
 * <h2>Exception inventory</h2>
 * <ul>
 *   <li>{@link #noCyclesBetweenDomains} — dto, model, dsl, shared, export, architecture,
 *       catalog, relations, versioning, workspace are excluded from the cycle check because
 *       they are cross-cutting shared packages. Future work: extract interfaces to
 *       {@code shared/} to resolve these cycles and remove exceptions.</li>
 *   <li>{@link #controllersShouldNotAccessRepositories} — security controllers are
 *       excluded because Spring Security patterns commonly involve direct repository
 *       access for user/role management. Remove this exception once a
 *       {@code SecurityUserFacade} is introduced.</li>
 *   <li>{@link #domainModuleShouldBeSpringFree} — no exceptions; {@code com.taxonomy.dto},
 *       {@code com.taxonomy.model}, and {@code com.taxonomy.pipeline} must remain
 *       Spring-free forever.</li>
 *   <li>{@link #dslFrameworkCodeShouldBeSpringFree} — {@code com.taxonomy.dsl.storage}
 *       and {@code com.taxonomy.dsl.export} are excluded because they live in
 *       {@code taxonomy-app} and provide Spring-based persistence/export adapters for
 *       the otherwise Spring-free DSL module.</li>
 *   <li>{@link #exportFrameworkCodeShouldBeSpringFree} — {@code com.taxonomy.export.service}
 *       and {@code com.taxonomy.export.controller} are excluded because they live in
 *       {@code taxonomy-app} and expose Spring MVC endpoints / service beans for the
 *       otherwise Spring-free export module.</li>
 *   <li>{@link #dslFrameworkCodeShouldNotDependOnAppPackages} — same storage/export
 *       exclusions apply.</li>
 *   <li>{@link #exportFrameworkCodeShouldNotDependOnAppPackages} — same service/controller
 *       exclusions apply.</li>
 *   <li>{@link #IMPLICIT_WORKSPACE_RESOLUTION_ALLOWLIST} — services still on the migration
 *       allowlist; each entry should be removed once the service is updated to accept
 *       {@code WorkspaceContext} as an explicit parameter.</li>
 * </ul>
 *
 * <p>See {@code docs/dev/08-archunit-exceptions.md} for the detailed per-entry
 * rationale and removal conditions.
 */
@AnalyzeClasses(packages = "com.taxonomy", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private record AllowlistEntry(String className, String reason, String removalCondition) {
    }

    /**
     * Small allowlist for controllers that still use repositories directly.
     *
     * <p>Removal condition is documented per entry.
     */
    private static final List<AllowlistEntry> CONTROLLER_REPOSITORY_ACCESS_ALLOWLIST = List.of(
            new AllowlistEntry(
                    "com.taxonomy.security.controller.ChangePasswordController",
                    "Current password-change flow still validates and updates AppUser directly.",
                    "Remove once password change is handled via a dedicated security service/facade."),
            new AllowlistEntry(
                    "com.taxonomy.security.controller.UserManagementController",
                    "Current admin user-management endpoints still orchestrate user/role persistence directly.",
                    "Remove once user/role CRUD orchestration is moved to a dedicated security service/facade.")
    );
    private static final Set<String> CONTROLLER_REPOSITORY_ACCESS_ALLOWLIST_CLASS_NAMES =
            CONTROLLER_REPOSITORY_ACCESS_ALLOWLIST.stream()
                    .map(AllowlistEntry::className)
                    .collect(Collectors.toUnmodifiableSet());

    /**
     * Temporary exceptions to the "service classes must not resolve request context implicitly" rule.
     *
     * <p>Each class still calls {@code resolveCurrentContext()} and/or
     * {@code resolveCurrentUsername()} internally and therefore remains on the
     * documented migration allowlist in {@code docs/dev/05-workspace-git-context.md}.
     */
    private static final List<AllowlistEntry> IMPLICIT_WORKSPACE_RESOLUTION_ALLOWLIST = List.of(
            new AllowlistEntry(
                    "com.taxonomy.catalog.service.CatalogFacade",
                    "Legacy catalog endpoints still build workspace-aware response state internally.",
                    "Remove once callers pass resolved username/context explicitly to catalog service methods."),
            new AllowlistEntry(
                    "com.taxonomy.dsl.export.DslMaterializeService",
                    "DSL materialization still switches between shared/workspace routing internally.",
                    "Remove once materialization receives explicit WorkspaceContext from request boundary."),
            new AllowlistEntry(
                    "com.taxonomy.relations.service.GraphSearchService",
                    "Graph search still resolves current workspace internally for relation queries.",
                    "Remove once graph search APIs accept WorkspaceContext from boundary/facade."),
            new AllowlistEntry(
                    "com.taxonomy.relations.service.RelationProposalService",
                    "Proposal CRUD still derives active workspace internally.",
                    "Remove once proposal operations receive WorkspaceContext from boundary/facade."),
            new AllowlistEntry(
                    "com.taxonomy.versioning.service.DslOperationsFacade",
                    "Remaining DSL/versioning operations still resolve workspace context inside the facade.",
                    "Remove once all facade operations are context-explicit."),
            new AllowlistEntry(
                    "com.taxonomy.versioning.service.HypothesisService",
                    "Hypothesis persistence/listing still resolves active workspace internally.",
                    "Remove once hypothesis methods receive WorkspaceContext from request boundary."),
            new AllowlistEntry(
                    "com.taxonomy.versioning.service.SelectiveTransferService",
                    "Selective transfer still resolves current workspace/user for navigation state.",
                    "Remove once selective transfer APIs become context-explicit.")
    );
    private static final Set<String> IMPLICIT_WORKSPACE_RESOLUTION_ALLOWLIST_CLASS_NAMES =
            IMPLICIT_WORKSPACE_RESOLUTION_ALLOWLIST.stream()
                    .map(AllowlistEntry::className)
                    .collect(Collectors.toUnmodifiableSet());

    private static DescribedPredicate<JavaClass> controllerClassesOutsideRepositoryAllowlist() {
        return new DescribedPredicate<>("controller classes outside repository-access allowlist") {
            @Override
            public boolean test(JavaClass input) {
                return input.getPackageName().contains(".controller")
                        && !CONTROLLER_REPOSITORY_ACCESS_ALLOWLIST_CLASS_NAMES.contains(input.getName());
            }
        };
    }

    private static DescribedPredicate<JavaClass> serviceClassesOutsideImplicitWorkspaceAllowlist() {
        return new DescribedPredicate<>("service classes outside the implicit workspace allowlist") {
            @Override
            public boolean test(JavaClass input) {
                return input.isAnnotatedWith(Service.class)
                        && !WorkspaceResolver.class.getName().equals(input.getName())
                        && !WorkspaceContextResolver.class.getName().equals(input.getName())
                        && !IMPLICIT_WORKSPACE_RESOLUTION_ALLOWLIST_CLASS_NAMES.contains(input.getName());
            }
        };
    }

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
            .that(controllerClassesOutsideRepositoryAllowlist())
            .should().dependOnClassesThat()
            .resideInAPackage("..repository..")
            .because("Controllers should use services/facades/DTOs; repository access is restricted to documented allowlist");

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

    @ArchTest
    static final ArchRule servicesShouldNotResolveCurrentUsernameImplicitly = noClasses()
            .that(serviceClassesOutsideImplicitWorkspaceAllowlist())
            .should().callMethod(WorkspaceResolver.class, "resolveCurrentUsername")
            .because("request boundaries should resolve usernames once and pass them explicitly");

    @ArchTest
    static final ArchRule servicesShouldNotResolveCurrentWorkspaceContextImplicitly = noClasses()
            .that(serviceClassesOutsideImplicitWorkspaceAllowlist())
            .should().callMethod(WorkspaceResolver.class, "resolveCurrentContext")
            .orShould().callMethod(WorkspaceContextResolver.class, "resolveCurrentContext")
            .because("request boundaries should resolve WorkspaceContext once and pass it explicitly");

    // -----------------------------------------------------------------------
    // Issue 367: Spring-free module boundary rules
    // -----------------------------------------------------------------------

    /**
     * {@code taxonomy-domain} packages must not use any Spring annotations or types.
     *
     * <p>Covered packages: {@code com.taxonomy.dto}, {@code com.taxonomy.model},
     * {@code com.taxonomy.pipeline}. No exceptions — these packages must remain
     * framework-free so they can be used by all modules without a Spring dependency.
     */
    @ArchTest
    static final ArchRule domainModuleShouldBeSpringFree = noClasses()
            .that().resideInAnyPackage(
                    "com.taxonomy.dto..",
                    "com.taxonomy.model..",
                    "com.taxonomy.pipeline..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..")
            .because("taxonomy-domain packages (dto, model, pipeline) must be Spring-free");

    /**
     * Framework-free packages of {@code taxonomy-dsl} must not use Spring.
     *
     * <p>Covered packages: {@code com.taxonomy.dsl.ast}, {@code .diff}, {@code .mapper},
     * {@code .mapping}, {@code .model}, {@code .parser}, {@code .serializer},
     * {@code .validation}.
     *
     * <p>Excluded: {@code com.taxonomy.dsl.storage} and {@code com.taxonomy.dsl.export}
     * — these are Spring-based adapter packages in {@code taxonomy-app} that provide
     * persistence and export integration for the DSL engine.
     * Remove this rule's exclusions once those adapters are moved to a dedicated
     * adapter module.
     */
    @ArchTest
    static final ArchRule dslFrameworkCodeShouldBeSpringFree = noClasses()
            .that().resideInAnyPackage(
                    "com.taxonomy.dsl.ast..",
                    "com.taxonomy.dsl.diff..",
                    "com.taxonomy.dsl.mapper..",
                    "com.taxonomy.dsl.mapping..",
                    "com.taxonomy.dsl.model..",
                    "com.taxonomy.dsl.parser..",
                    "com.taxonomy.dsl.serializer..",
                    "com.taxonomy.dsl.validation..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..")
            .because("taxonomy-dsl framework-free packages must not depend on Spring "
                    + "(storage and export adapters in taxonomy-app are excluded)");

    /**
     * Framework-free packages of {@code taxonomy-export} must not use Spring.
     *
     * <p>Covered packages: {@code com.taxonomy.archimate}, {@code com.taxonomy.diagram},
     * {@code com.taxonomy.visio}, and the root {@code com.taxonomy.export} (direct classes only).
     *
     * <p>Excluded: {@code com.taxonomy.export.service} and
     * {@code com.taxonomy.export.controller} — these are Spring MVC adapter packages
     * in {@code taxonomy-app}. Remove exclusions once those adapters are moved to a
     * dedicated adapter module.
     */
    @ArchTest
    static final ArchRule exportFrameworkCodeShouldBeSpringFree = noClasses()
            .that().resideInAnyPackage(
                    "com.taxonomy.archimate..",
                    "com.taxonomy.diagram..",
                    "com.taxonomy.visio..",
                    "com.taxonomy.export")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..")
            .because("taxonomy-export framework-free packages must not depend on Spring "
                    + "(service and controller adapters in taxonomy-app are excluded)");

    /**
     * Framework-free DSL packages must not depend on {@code taxonomy-app} packages.
     *
     * <p>Same package scope as {@link #dslFrameworkCodeShouldBeSpringFree}.
     * The {@code taxonomy-dsl} module must remain usable without any runtime dependency
     * on the application layer.
     */
    @ArchTest
    static final ArchRule dslFrameworkCodeShouldNotDependOnAppPackages = noClasses()
            .that().resideInAnyPackage(
                    "com.taxonomy.dsl.ast..",
                    "com.taxonomy.dsl.diff..",
                    "com.taxonomy.dsl.mapper..",
                    "com.taxonomy.dsl.mapping..",
                    "com.taxonomy.dsl.model..",
                    "com.taxonomy.dsl.parser..",
                    "com.taxonomy.dsl.serializer..",
                    "com.taxonomy.dsl.validation..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "com.taxonomy.catalog..",
                    "com.taxonomy.analysis..",
                    "com.taxonomy.architecture..",
                    "com.taxonomy.relations..",
                    "com.taxonomy.search..",
                    "com.taxonomy.versioning..",
                    "com.taxonomy.workspace..",
                    "com.taxonomy.provenance..",
                    "com.taxonomy.preferences..",
                    "com.taxonomy.security..",
                    "com.taxonomy.shared..",
                    "com.taxonomy.export.service..",
                    "com.taxonomy.export.controller..")
            .because("taxonomy-dsl framework-free packages must not depend on taxonomy-app packages");

    /**
     * Framework-free export packages must not depend on {@code taxonomy-app} packages.
     *
     * <p>Same package scope as {@link #exportFrameworkCodeShouldBeSpringFree}.
     * The {@code taxonomy-export} module must remain usable without any runtime
     * dependency on the application layer.
     */
    @ArchTest
    static final ArchRule exportFrameworkCodeShouldNotDependOnAppPackages = noClasses()
            .that().resideInAnyPackage(
                    "com.taxonomy.archimate..",
                    "com.taxonomy.diagram..",
                    "com.taxonomy.visio..",
                    "com.taxonomy.export")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "com.taxonomy.catalog..",
                    "com.taxonomy.analysis..",
                    "com.taxonomy.architecture..",
                    "com.taxonomy.relations..",
                    "com.taxonomy.search..",
                    "com.taxonomy.versioning..",
                    "com.taxonomy.workspace..",
                    "com.taxonomy.provenance..",
                    "com.taxonomy.preferences..",
                    "com.taxonomy.security..",
                    "com.taxonomy.shared..",
                    "com.taxonomy.export.service..",
                    "com.taxonomy.export.controller..")
            .because("taxonomy-export framework-free packages must not depend on taxonomy-app packages");
}
