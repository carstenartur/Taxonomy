package com.taxonomy.shared.config;

import com.taxonomy.export.ConfigurableDiagramSelectionPolicy;
import com.taxonomy.export.DiagramSelectionConfig;
import com.taxonomy.export.DiagramSelectionPolicy;
import com.taxonomy.preferences.PreferencesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExportConfig#resolvePolicy(PreferencesService)}.
 */
class ExportConfigTest {

    private final PreferencesService prefs = mock(PreferencesService.class);

    @Test
    void defaultImpactPolicyResolved() {
        when(prefs.getString("diagram.policy", "defaultImpact")).thenReturn("defaultImpact");
        DiagramSelectionPolicy policy = ExportConfig.resolvePolicy(prefs);
        assertThat(policy).isInstanceOf(ConfigurableDiagramSelectionPolicy.class);
        var config = ((ConfigurableDiagramSelectionPolicy) policy).config();
        assertThat(config).isEqualTo(DiagramSelectionConfig.defaultImpact());
    }

    @Test
    void leafOnlyPolicyResolved() {
        when(prefs.getString("diagram.policy", "defaultImpact")).thenReturn("leafOnly");
        DiagramSelectionPolicy policy = ExportConfig.resolvePolicy(prefs);
        var config = ((ConfigurableDiagramSelectionPolicy) policy).config();
        assertThat(config).isEqualTo(DiagramSelectionConfig.leafOnly());
    }

    @Test
    void clusteringPolicyResolved() {
        when(prefs.getString("diagram.policy", "defaultImpact")).thenReturn("clustering");
        DiagramSelectionPolicy policy = ExportConfig.resolvePolicy(prefs);
        var config = ((ConfigurableDiagramSelectionPolicy) policy).config();
        assertThat(config).isEqualTo(DiagramSelectionConfig.clustering());
    }

    @Test
    void tracePolicyResolved() {
        when(prefs.getString("diagram.policy", "defaultImpact")).thenReturn("trace");
        DiagramSelectionPolicy policy = ExportConfig.resolvePolicy(prefs);
        var config = ((ConfigurableDiagramSelectionPolicy) policy).config();
        assertThat(config).isEqualTo(DiagramSelectionConfig.trace());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "INVALID", "", "foo"})
    void unknownPolicyFallsBackToDefaultImpact(String policyName) {
        when(prefs.getString("diagram.policy", "defaultImpact")).thenReturn(policyName);
        DiagramSelectionPolicy policy = ExportConfig.resolvePolicy(prefs);
        var config = ((ConfigurableDiagramSelectionPolicy) policy).config();
        assertThat(config).isEqualTo(DiagramSelectionConfig.defaultImpact());
    }
}
