package com.taxonomy;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Enforces cycle freedom for core bounded contexts while allowing only the
 * narrow, temporary edges recorded in .github/architecture-exceptions.json.
 */
@AnalyzeClasses(packages = "com.taxonomy", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureCycleBoundaryTest {

    static final Set<String> DOCUMENTED_EXCEPTION_IDS = Set.of(
            "cycle-architecture-to-export",
            "cycle-export-to-architecture",
            "cycle-catalog-to-relations",
            "cycle-relations-to-catalog",
            "cycle-versioning-to-workspace",
            "cycle-workspace-to-versioning",
            "cycle-dsl-adapter-outbound",
            "cycle-dsl-adapter-inbound"
    );

    private static final String[] STRUCTURAL_SHARED_PACKAGES = {
            "com.taxonomy.dto..",
            "com.taxonomy.model..",
            "com.taxonomy.shared.."
    };

    private static final String[] DSL_ADAPTER_PACKAGES = {
            "com.taxonomy.dsl.storage..",
            "com.taxonomy.dsl.export.."
    };

    @ArchTest
    static final ArchRule coreDomainSlicesShouldBeFreeOfUndocumentedCycles = slices()
            .matching("com.taxonomy.(*)..")
            .should().beFreeOfCycles()
            // Structural contracts are intentionally shared and are not bounded contexts.
            .ignoreDependency(resideInAnyPackage(STRUCTURAL_SHARED_PACKAGES), alwaysTrue())
            .ignoreDependency(alwaysTrue(), resideInAnyPackage(STRUCTURAL_SHARED_PACKAGES))
            // Known bidirectional edges, each represented in the checked ledger.
            .ignoreDependency(
                    resideInAnyPackage("com.taxonomy.architecture.."),
                    resideInAnyPackage("com.taxonomy.export.."))
            .ignoreDependency(
                    resideInAnyPackage("com.taxonomy.export.."),
                    resideInAnyPackage("com.taxonomy.architecture.."))
            .ignoreDependency(
                    resideInAnyPackage("com.taxonomy.catalog.."),
                    resideInAnyPackage("com.taxonomy.relations.."))
            .ignoreDependency(
                    resideInAnyPackage("com.taxonomy.relations.."),
                    resideInAnyPackage("com.taxonomy.catalog.."))
            .ignoreDependency(
                    resideInAnyPackage("com.taxonomy.versioning.."),
                    resideInAnyPackage("com.taxonomy.workspace.."))
            .ignoreDependency(
                    resideInAnyPackage("com.taxonomy.workspace.."),
                    resideInAnyPackage("com.taxonomy.versioning.."))
            // Spring/JGit DSL adapters still live below the otherwise pure DSL root.
            .ignoreDependency(resideInAnyPackage(DSL_ADAPTER_PACKAGES), alwaysTrue())
            .ignoreDependency(alwaysTrue(), resideInAnyPackage(DSL_ADAPTER_PACKAGES))
            .because("new core-domain cycles require a reviewed, expiring ledger entry");
}