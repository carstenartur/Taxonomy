package com.taxonomy.shared.features;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FeatureAssemblyTest {
    @Test void coreAndIndependentFeaturesNeedNoOptionalImplementation() {
        assertThat(FeatureAssembly.validate(Set.of()).installed()).isEmpty();
        for (String feature : List.of("templates", "architecture", "interop"))
            assertThat(FeatureAssembly.validate(Set.of(feature)).has(feature)).isTrue();
    }
    @Test void rejectsEachMissingPrerequisiteAndUnknownIds() {
        assertThatIllegalArgumentException().isThrownBy(() -> FeatureAssembly.validate(Set.of("reporting")))
                .withMessage("reporting requires templates");
        assertThatIllegalArgumentException().isThrownBy(() -> FeatureAssembly.validate(Set.of("analysis")))
                .withMessage("analysis requires architecture");
        assertThatIllegalArgumentException().isThrownBy(() -> FeatureAssembly.validate(Set.of("architecture", "analysis", "portfolio")))
                .withMessage("portfolio requires reporting");
        assertThatIllegalArgumentException().isThrownBy(() -> FeatureAssembly.validate(Set.of("unknown")))
                .withMessageContaining("Unknown startup feature");
    }
    @Test void capturesAnImmutableInstallationAndKeepsActualReportingDependency() {
        var installed = new HashSet<>(Set.of("templates", "architecture", "reporting", "analysis", "portfolio", "interop"));
        var assembly = FeatureAssembly.validate(installed); installed.clear();
        assertThat(assembly.installed()).hasSize(6);
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> assembly.installed().clear());
    }
    @Test void preflightInventoriesResourcesWithoutLinkingFeatureClasses() {
        var loader = new ClassLoader(null) {
            @Override public Enumeration<java.net.URL> getResources(String name) {
                try { return Collections.enumeration(name.contains("reporting/config/")
                        ? List.of(new java.net.URI("file:/operator/reporting.jar").toURL()) : List.of()); }
                catch (Exception impossible) { throw new AssertionError(impossible); }
            }
            @Override public Class<?> loadClass(String name) { throw new AssertionError("Must not resolve feature classes: " + name); }
        };
        assertThatIllegalArgumentException().isThrownBy(() -> FeatureAssembly.discover(loader))
                .withMessage("reporting requires templates");
    }
    @Test void rejectsDuplicateFeatureArtifactsBeforeBootstrap() {
        var loader = new ClassLoader(null) {
            @Override public Enumeration<java.net.URL> getResources(String name) {
                try { return Collections.enumeration(name.contains("templates/DocumentTemplateService")
                        ? List.of(new java.net.URI("file:/first.jar").toURL(), new java.net.URI("file:/second.jar").toURL()) : List.of()); }
                catch (Exception impossible) { throw new AssertionError(impossible); }
            }
        };
        assertThatIllegalArgumentException().isThrownBy(() -> FeatureAssembly.discover(loader))
                .withMessage("Duplicate startup feature: templates");
    }
}
