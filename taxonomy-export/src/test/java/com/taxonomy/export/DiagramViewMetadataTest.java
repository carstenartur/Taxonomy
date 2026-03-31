package com.taxonomy.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DiagramViewMetadata}.
 */
class DiagramViewMetadataTest {

    @Test
    void defaultImpactProducesExpectedTitle() {
        DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(
                DiagramSelectionConfig.defaultImpact(), "defaultImpact");
        assertThat(meta.viewTitle()).isEqualTo("Architecture Impact View");
        assertThat(meta.policyKey()).isEqualTo("defaultImpact");
        assertThat(meta.containmentEnabled()).isTrue();
        assertThat(meta.activeRules()).contains("Root nodes suppressed");
        assertThat(meta.activeRules()).contains("Single-child intermediates collapsed");
        assertThat(meta.activeRules()).contains("Multi-child intermediates as clusters");
    }

    @Test
    void clusteringProducesExpectedTitle() {
        DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(
                DiagramSelectionConfig.clustering(), "clustering");
        assertThat(meta.viewTitle()).isEqualTo("Clustered Impact View");
        assertThat(meta.policyKey()).isEqualTo("clustering");
        assertThat(meta.containmentEnabled()).isTrue();
    }

    @Test
    void leafOnlyProducesExpectedTitle() {
        DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(
                DiagramSelectionConfig.leafOnly(), "leafOnly");
        assertThat(meta.viewTitle()).isEqualTo("Leaf-Only Showcase View");
        assertThat(meta.policyKey()).isEqualTo("leafOnly");
        assertThat(meta.activeRules()).contains("Leaf-only mode");
    }

    @Test
    void traceProducesExpectedTitle() {
        DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(
                DiagramSelectionConfig.trace(), "trace");
        assertThat(meta.viewTitle()).isEqualTo("Scoring Trace");
        assertThat(meta.policyKey()).isEqualTo("trace");
        assertThat(meta.containmentEnabled()).isFalse();
    }

    @Test
    void nullPolicyKeyDefaultsToImpact() {
        DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(
                DiagramSelectionConfig.defaultImpact(), null);
        assertThat(meta.viewTitle()).isEqualTo("Architecture Impact View");
        assertThat(meta.policyKey()).isEqualTo("defaultImpact");
    }

    @Test
    void descriptionIsNeverNull() {
        for (String key : new String[]{"defaultImpact", "leafOnly", "clustering", "trace"}) {
            DiagramSelectionConfig config = switch (key) {
                case "leafOnly" -> DiagramSelectionConfig.leafOnly();
                case "clustering" -> DiagramSelectionConfig.clustering();
                case "trace" -> DiagramSelectionConfig.trace();
                default -> DiagramSelectionConfig.defaultImpact();
            };
            DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(config, key);
            assertThat(meta.viewDescription()).isNotNull().isNotBlank();
        }
    }

    @Test
    void activeRulesReflectConfigFlags() {
        DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(
                DiagramSelectionConfig.trace(), "trace");
        // Trace mode disables all suppression — no suppression rules should appear
        assertThat(meta.activeRules())
                .doesNotContain("Root nodes suppressed")
                .doesNotContain("Scaffolding nodes suppressed")
                .doesNotContain("Single-child intermediates collapsed")
                .doesNotContain("Multi-child intermediates as clusters")
                .doesNotContain("Leaf-only mode");
    }
}
