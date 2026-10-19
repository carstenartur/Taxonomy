package com.taxonomy.analysis;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import com.taxonomy.composition.analysis.PreferenceAnalysisRuntimeSettings;
import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.preferences.storage.PreferencesGitRepository;
import com.taxonomy.workspace.service.WorkspaceViewContextReadPort;
import com.taxonomy.versioning.service.RepositoryStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisRuntimeSettingsPortTest {
    @Test
    void existingApplicationSettingsAndWorkspaceProviderImplementReadOnlyPorts() throws NoSuchMethodException {
        assertThat(AnalysisRuntimeSettings.class).isAssignableFrom(com.taxonomy.composition.analysis.PreferenceAnalysisRuntimeSettings.class);
        assertThat(AnalysisRuntimeSettings.class.isAssignableFrom(PreferencesService.class)).isFalse();
        assertThat(WorkspaceViewContextReadPort.class).isAssignableFrom(RepositoryStateService.class);
        // The public port contract is independent of private coverage instrumentation.
        assertThat(Arrays.stream(AnalysisRuntimeSettings.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())))
                .extracting(Method::getName).containsExactlyInAnyOrder("getInt", "getString", "getBoolean", "snapshot");
        var integerSetting = AnalysisRuntimeSettings.class.getDeclaredMethod("getInt", String.class, int.class);
        assertThat(integerSetting.getReturnType()).isEqualTo(int.class);
        assertThat(Arrays.stream(AnalysisRuntimeSettings.class.getDeclaredMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers())))
                .containsExactly(integerSetting);
        var stringSetting = AnalysisRuntimeSettings.class.getDeclaredMethod("getString", String.class, String.class);
        assertThat(stringSetting.getReturnType()).isEqualTo(String.class);
        assertThat(stringSetting.isDefault()).isTrue();
        var booleanSetting = AnalysisRuntimeSettings.class.getDeclaredMethod("getBoolean", String.class, boolean.class);
        assertThat(booleanSetting.getReturnType()).isEqualTo(boolean.class);
        assertThat(booleanSetting.isDefault()).isTrue();
        var snapshot = AnalysisRuntimeSettings.class.getDeclaredMethod("snapshot");
        assertThat(snapshot.getReturnType()).isEqualTo(AnalysisRuntimeSettings.class);
        assertThat(snapshot.isDefault()).isTrue();

        AnalysisRuntimeSettings optionalAdapter = (key, fallback) -> fallback;
        assertThat(optionalAdapter.getInt("llm.rpm", 5)).isEqualTo(5);
        assertThat(optionalAdapter.getString("analysis.reason-length", "detailed")).isEqualTo("detailed");
        assertThat(optionalAdapter.getBoolean("analysis.relations.auto-continue", true)).isTrue();
        assertThat(optionalAdapter.snapshot()).isSameAs(optionalAdapter);
    }

    @Test
    void capturedPreferenceViewKeepsOneCommittedVersionAfterRuntimeUpdates() throws Exception {
        try (var repository = new PreferencesGitRepository()) {
            var preferences = new PreferencesService(repository, new ObjectMapper());
            preferences.init();
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("preferences", preferences);
            var adapter = new PreferenceAnalysisRuntimeSettings(beans.getBeanProvider(PreferencesService.class));
            var frozen = adapter.snapshot();

            preferences.update(Map.of("analysis.relations.max-calls", 1024,
                    "analysis.relations.max-total-calls", 8192,
                    "analysis.relations.auto-continue", false,
                    "analysis.reason-length", "detailed"), "tester");

            assertThat(frozen.getInt("analysis.relations.max-calls", -1)).isEqualTo(256);
            assertThat(frozen.getInt("analysis.relations.max-total-calls", -1)).isEqualTo(4096);
            assertThat(frozen.getBoolean("analysis.relations.auto-continue", false)).isTrue();
            assertThat(frozen.getString("analysis.reason-length", "missing")).isEqualTo("standard");
            assertThat(adapter.getInt("analysis.relations.max-calls", -1)).isEqualTo(1024);
            assertThat(adapter.getBoolean("analysis.relations.auto-continue", true)).isFalse();
            assertThat(adapter.snapshot().getString("analysis.reason-length", "missing")).isEqualTo("detailed");
        }
    }
}
