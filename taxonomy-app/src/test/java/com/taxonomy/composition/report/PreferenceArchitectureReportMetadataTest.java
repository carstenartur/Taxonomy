package com.taxonomy.composition.report;

import com.taxonomy.export.DiagramSelectionConfig;
import com.taxonomy.export.DiagramViewMetadata;
import com.taxonomy.preferences.PreferencesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PreferenceArchitectureReportMetadataTest {
    @ParameterizedTest
    @ValueSource(strings = {"defaultImpact", "leafOnly", "clustering", "trace", "unknown-policy"})
    void preservesEveryPolicyAndUnknownPolicyFallback(String name) {
        PreferencesService preferences = mock(PreferencesService.class);
        when(preferences.getString("diagram.policy", "defaultImpact")).thenReturn(name);
        DiagramSelectionConfig config = switch (name) {
            case "leafOnly" -> DiagramSelectionConfig.leafOnly();
            case "clustering" -> DiagramSelectionConfig.clustering();
            case "trace" -> DiagramSelectionConfig.trace();
            default -> DiagramSelectionConfig.defaultImpact();
        };
        assertThat(new PreferenceArchitectureReportMetadata(preferences).resolve())
                .isEqualTo(DiagramViewMetadata.fromConfig(config, name));
    }

    @Test
    void readsChangedPreferencesOnEveryInvocation() {
        PreferencesService preferences = mock(PreferencesService.class);
        when(preferences.getString("diagram.policy", "defaultImpact")).thenReturn("leafOnly", "trace");
        var provider = new PreferenceArchitectureReportMetadata(preferences);
        assertThat(provider.resolve()).isEqualTo(DiagramViewMetadata.fromConfig(DiagramSelectionConfig.leafOnly(), "leafOnly"));
        assertThat(provider.resolve()).isEqualTo(DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace"));
        verify(preferences, times(2)).getString("diagram.policy", "defaultImpact");
    }

    @Test
    void doesNotHidePreferenceReadFailures() {
        PreferencesService preferences = mock(PreferencesService.class);
        var failure = new IllegalStateException("preferences unavailable");
        when(preferences.getString("diagram.policy", "defaultImpact")).thenThrow(failure);
        var provider = new PreferenceArchitectureReportMetadata(preferences);
        assertThatThrownBy(provider::resolve).isSameAs(failure);
    }
}
