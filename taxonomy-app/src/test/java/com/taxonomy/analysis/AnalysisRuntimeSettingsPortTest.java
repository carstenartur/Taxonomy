package com.taxonomy.analysis;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.workspace.service.WorkspaceViewContextReadPort;
import com.taxonomy.versioning.service.RepositoryStateService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisRuntimeSettingsPortTest {
    @Test
    void existingApplicationSettingsAndWorkspaceProviderImplementReadOnlyPorts() {
        assertThat(AnalysisRuntimeSettings.class).isAssignableFrom(PreferencesService.class);
        assertThat(WorkspaceViewContextReadPort.class).isAssignableFrom(RepositoryStateService.class);
        assertThat(AnalysisRuntimeSettings.class.getDeclaredMethods()).hasSize(1);
        assertThat(AnalysisRuntimeSettings.class.getDeclaredMethods()[0].getName()).isEqualTo("getInt");
    }
}
