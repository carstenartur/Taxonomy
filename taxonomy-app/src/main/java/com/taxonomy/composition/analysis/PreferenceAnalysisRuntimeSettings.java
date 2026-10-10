package com.taxonomy.composition.analysis;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.shared.features.ConditionalOnFeature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Lazy one-way adapter; core preferences have no optional analysis type in their class signature. */
@Component
@ConditionalOnFeature("analysis")
public final class PreferenceAnalysisRuntimeSettings implements AnalysisRuntimeSettings {
    private final ObjectProvider<PreferencesService> preferences;
    public PreferenceAnalysisRuntimeSettings(ObjectProvider<PreferencesService> preferences) { this.preferences = preferences; }
    @Override public int getInt(String key, int defaultValue) { return preferences.getObject().getInt(key, defaultValue); }
}
