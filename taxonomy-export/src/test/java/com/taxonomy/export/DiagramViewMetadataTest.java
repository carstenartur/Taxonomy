package com.taxonomy.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DiagramViewMetadata}.
 *
 * <p>Since the record now emits i18n message keys (not translated strings),
 * tests verify the key patterns and structural invariants.</p>
 */
class DiagramViewMetadataTest {

    @Test
    void defaultImpactProducesExpectedKeys() {
        DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(
                DiagramSelectionConfig.defaultImpact(), "defaultImpact");
        assertThat(meta.viewTitle()).isEqualTo("archview.policy.title.defaultImpact");
        assertThat(meta.viewDescription()).isEqualTo("archview.policy.desc.defaultImpact");
        assertThat(meta.policyKey()).isEqualTo("defaultImpact");
        assertThat(meta.containmentEnabled()).isTrue();
        assertThat(meta.activeRules()).contains("archview.rule.suppress.roots");
        assertThat(meta.activeRules()).contains("archview.rule.collapse.single.child");
        assertThat(meta.activeRules()).contains("archview.rule.cluster.multi.child");
    }

    @Test
    void clusteringProducesExpectedKeys() {
        DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(
                DiagramSelectionConfig.clustering(), "clustering");
        assertThat(meta.viewTitle()).isEqualTo("archview.policy.title.clustering");
        assertThat(meta.policyKey()).isEqualTo("clustering");
        assertThat(meta.containmentEnabled()).isTrue();
    }

    @Test
    void leafOnlyProducesExpectedKeys() {
        DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(
                DiagramSelectionConfig.leafOnly(), "leafOnly");
        assertThat(meta.viewTitle()).isEqualTo("archview.policy.title.leafOnly");
        assertThat(meta.policyKey()).isEqualTo("leafOnly");
        assertThat(meta.activeRules()).contains("archview.rule.leaf.only");
    }

    @Test
    void traceProducesExpectedKeys() {
        DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(
                DiagramSelectionConfig.trace(), "trace");
        assertThat(meta.viewTitle()).isEqualTo("archview.policy.title.trace");
        assertThat(meta.viewDescription()).isEqualTo("archview.policy.desc.trace");
        assertThat(meta.policyKey()).isEqualTo("trace");
        assertThat(meta.containmentEnabled()).isFalse();
    }

    @Test
    void nullPolicyKeyDefaultsToImpact() {
        DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(
                DiagramSelectionConfig.defaultImpact(), null);
        assertThat(meta.viewTitle()).isEqualTo("archview.policy.title.defaultImpact");
        assertThat(meta.policyKey()).isEqualTo("defaultImpact");
    }

    @Test
    void descriptionKeysAreNeverNull() {
        for (String key : new String[]{"defaultImpact", "leafOnly", "clustering", "trace"}) {
            DiagramSelectionConfig config = switch (key) {
                case "leafOnly" -> DiagramSelectionConfig.leafOnly();
                case "clustering" -> DiagramSelectionConfig.clustering();
                case "trace" -> DiagramSelectionConfig.trace();
                default -> DiagramSelectionConfig.defaultImpact();
            };
            DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(config, key);
            assertThat(meta.viewDescription()).isNotNull().startsWith("archview.policy.desc.");
        }
    }

    @Test
    void activeRulesReflectConfigFlags() {
        DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(
                DiagramSelectionConfig.trace(), "trace");
        // Trace mode disables all suppression — no suppression rule keys should appear
        assertThat(meta.activeRules())
                .doesNotContain("archview.rule.suppress.roots")
                .doesNotContain("archview.rule.suppress.scaffolding")
                .doesNotContain("archview.rule.collapse.single.child")
                .doesNotContain("archview.rule.cluster.multi.child")
                .doesNotContain("archview.rule.leaf.only");
    }

    @Test
    void allRuleKeysStartWithPrefix() {
        DiagramViewMetadata meta = DiagramViewMetadata.fromConfig(
                DiagramSelectionConfig.defaultImpact(), "defaultImpact");
        assertThat(meta.activeRules()).allSatisfy(
                rule -> assertThat(rule).startsWith("archview.rule."));
    }
}
