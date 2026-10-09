package com.taxonomy;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;

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
            "cycle-dsl-adapter-inbound",
            "cycle-analysis-usecase-to-export-metadata",
            "cycle-catalog-model-to-search-binder",
            "cycle-search-binder-to-catalog-model",
            "cycle-catalog-controller-to-versioning-state"
    );

    private static final String[] STRUCTURAL_SHARED_PACKAGES = {
            "com.taxonomy.dto..",
            "com.taxonomy.model..",
            "com.taxonomy.shared.."
    };

    private static final String[] DSL_ADAPTER_PACKAGES = {
            "com.taxonomy.dsl.export.."
    };

    @ArchTest
    static final ArchRule coreDomainSlicesShouldBeFreeOfUndocumentedCycles = slices()
            .assignedFrom(new SliceAssignment() {
                @Override
                public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
                    String packageName = javaClass.getPackageName();
                    if (packageName.equals("com.taxonomy.extension.api") || packageName.startsWith("com.taxonomy.extension.api."))
                        return SliceIdentifier.of("extension-api");
                    if (packageName.equals("com.taxonomy.extension.runtime") || packageName.startsWith("com.taxonomy.extension.runtime."))
                        return SliceIdentifier.of("extension-runtime");
                    if (packageName.equals("com.taxonomy.export.spi") || packageName.startsWith("com.taxonomy.export.spi.")
                            || ArchitectureSourceOwnership.belongsTo(ArchitectureSourceOwnership.repository(), javaClass, "taxonomy-export"))
                        return SliceIdentifier.of("export-core");
                    if (packageName.equals("com.taxonomy.reporting.api")
                            || packageName.startsWith("com.taxonomy.reporting.api.")) {
                        return SliceIdentifier.of("reporting-api");
                    }
                    if (!packageName.startsWith("com.taxonomy.")) {
                        return SliceIdentifier.ignore();
                    }
                    return SliceIdentifier.of(packageName.substring("com.taxonomy.".length()).split("\\.")[0]);
                }

                @Override
                public String getDescription() {
                    return "Taxonomy contexts with separate SDK, loader and export implementation owners";
                }
            })
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
            // Precisely scoped pre-existing edges exposed by replacing the old no-op rule.
            .ignoreDependency(
                    resideInAnyPackage("com.taxonomy.analysis.usecase.."),
                    resideInAnyPackage("com.taxonomy.export.."))
            .ignoreDependency(
                    resideInAnyPackage("com.taxonomy.catalog.model.."),
                    resideInAnyPackage("com.taxonomy.search.."))
            .ignoreDependency(
                    resideInAnyPackage("com.taxonomy.search.."),
                    resideInAnyPackage("com.taxonomy.catalog.model.."))
            .ignoreDependency(
                    resideInAnyPackage("com.taxonomy.catalog.controller.."),
                    resideInAnyPackage("com.taxonomy.versioning.service.."))
            // The Spring DSL export adapter still lives below the otherwise pure DSL root.
            .ignoreDependency(resideInAnyPackage(DSL_ADAPTER_PACKAGES), alwaysTrue())
            .ignoreDependency(alwaysTrue(), resideInAnyPackage(DSL_ADAPTER_PACKAGES))
            .because("new core-domain cycles require a reviewed, expiring ledger entry");
}
