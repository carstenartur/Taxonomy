package com.taxonomy.composition.analysis;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.shared.features.ConditionalOnFeature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Lazy one-way adapter; core preferences have no optional analysis type in their class signature. */
@Component
@ConditionalOnFeature("analysis")
public final class PreferenceAnalysisRuntimeSettings implements AnalysisRuntimeSettings {
    private final ObjectProvider<PreferencesService> preferences;
    public PreferenceAnalysisRuntimeSettings(ObjectProvider<PreferencesService> preferences) { this.preferences = preferences; }
    @Override public int getInt(String key, int defaultValue) { return preferences.getObject().getInt(key, defaultValue); }
    @Override public String getString(String key, String defaultValue) { return preferences.getObject().getString(key, defaultValue); }
    @Override public boolean getBoolean(String key, boolean defaultValue) { return preferences.getObject().getBoolean(key, defaultValue); }

    @Override
    public AnalysisRuntimeSettings snapshot() {
        Map<String, Object> captured = preferences.getObject().getAll();
        return new AnalysisRuntimeSettings() {
            public int getInt(String key, int defaultValue) {
                Object value = captured.get(key);
                if (value instanceof Number number) return number.intValue();
                if (value instanceof String text) {
                    try { return Integer.parseInt(text); } catch (NumberFormatException ignored) { }
                }
                return defaultValue;
            }
            public String getString(String key, String defaultValue) {
                Object value = captured.get(key);
                return value == null ? defaultValue : String.valueOf(value);
            }
            public boolean getBoolean(String key, boolean defaultValue) {
                Object value = captured.get(key);
                if (value instanceof Boolean flag) return flag;
                if (value instanceof String text) return Boolean.parseBoolean(text);
                return defaultValue;
            }
        };
    }
}
