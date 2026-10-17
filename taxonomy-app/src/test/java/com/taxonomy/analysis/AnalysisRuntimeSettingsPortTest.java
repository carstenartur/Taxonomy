package com.taxonomy.analysis;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.workspace.service.WorkspaceViewContextReadPort;
import com.taxonomy.versioning.service.RepositoryStateService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
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
                .extracting(Method::getName).containsExactlyInAnyOrder("getInt", "getString");
        var integerSetting = AnalysisRuntimeSettings.class.getDeclaredMethod("getInt", String.class, int.class);
        assertThat(integerSetting.getReturnType()).isEqualTo(int.class);
        assertThat(Arrays.stream(AnalysisRuntimeSettings.class.getDeclaredMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers())))
                .containsExactly(integerSetting);
        var stringSetting = AnalysisRuntimeSettings.class.getDeclaredMethod("getString", String.class, String.class);
        assertThat(stringSetting.getReturnType()).isEqualTo(String.class);
        assertThat(stringSetting.isDefault()).isTrue();

        AnalysisRuntimeSettings optionalAdapter = (key, fallback) -> fallback;
        assertThat(optionalAdapter.getInt("llm.rpm", 5)).isEqualTo(5);
        assertThat(optionalAdapter.getString("analysis.reason-length", "detailed")).isEqualTo("detailed");
    }
}
