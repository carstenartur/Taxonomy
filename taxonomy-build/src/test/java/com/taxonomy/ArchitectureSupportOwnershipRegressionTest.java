package com.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ArchitectureSupportOwnershipRegressionTest {

    private static final String APP = "taxonomy-app";
    private static final String FEATURE = "taxonomy-a";
    private static final String SUPPORT = "taxonomy-domain";
    private static final ArchitectureModuleGraph.Policy POLICY = new ArchitectureModuleGraph.Policy(
            APP,
            Set.of("AppConfig.java"),
            List.of(
                    new ArchitectureModuleGraph.Context("a", FEATURE, List.of("com.taxonomy.a..")),
                    new ArchitectureModuleGraph.Context("composition", APP,
                            List.of("com.taxonomy.composition..", "com.taxonomy.shared.."))));

    @Test
    void rejectsFeatureContextClassLeftInSupportModule() {
        var result = ArchitectureModuleGraph.evaluate(
                POLICY,
                Set.of(SUPPORT),
                Set.of(FEATURE, SUPPORT),
                List.of(
                        new ArchitectureModuleGraph.ClassOwner("com.taxonomy.a.Service", FEATURE, "Service.java"),
                        new ArchitectureModuleGraph.ClassOwner("com.taxonomy.a.Other", SUPPORT, "Other.java")),
                List.of());

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains("com.taxonomy.a.Other", SUPPORT, "planned owner is " + FEATURE));
    }

    @Test
    void stillAllowsSharedPackageClassInSupportModule() {
        var result = ArchitectureModuleGraph.evaluate(
                POLICY,
                Set.of(SUPPORT),
                Set.of(FEATURE, SUPPORT),
                List.of(
                        new ArchitectureModuleGraph.ClassOwner("com.taxonomy.a.Service", FEATURE, "Service.java"),
                        new ArchitectureModuleGraph.ClassOwner("com.taxonomy.shared.Contract", SUPPORT, "Contract.java")),
                List.of());

        assertThat(result.violations()).isEmpty();
    }
}
