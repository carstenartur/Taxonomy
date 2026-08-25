package com.taxonomy.preferences;

import com.taxonomy.preferences.storage.PreferencesGitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreferencesServiceCommitFailureTest {

    @Test
    void failedUpdateKeepsTheLastCommittedRuntimeSnapshot() {
        PreferencesService service = serviceWithFailingMutationRepository();

        assertThatThrownBy(() -> service.update(Map.of("llm.rpm", 9), "qa"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated Git commit failure");

        assertThat(service.getInt("llm.rpm", -1)).isEqualTo(5);
        assertThat(service.getString("dsl.project-name", "missing"))
                .isEqualTo("Existing project");
    }

    @Test
    void failedResetKeepsTheLastCommittedRuntimeSnapshot() {
        PreferencesService service = serviceWithFailingMutationRepository();
        ReflectionTestUtils.setField(service, "defaultLlmRpm", 12);
        ReflectionTestUtils.setField(service, "defaultDslProjectName", "Default project");

        assertThatThrownBy(() -> service.resetToDefaults("qa"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated Git commit failure");

        assertThat(service.getInt("llm.rpm", -1)).isEqualTo(5);
        assertThat(service.getString("dsl.project-name", "missing"))
                .isEqualTo("Existing project");
    }

    private static PreferencesService serviceWithFailingMutationRepository() {
        PreferencesService service = new PreferencesService(
                new FailingMutationRepository(),
                JsonMapper.builder().build());
        service.init();
        return service;
    }

    private static final class FailingMutationRepository
            extends PreferencesGitRepository {

        @Override
        public String readHead() {
            return """
                    {
                      "llm.rpm": 5,
                      "dsl.project-name": "Existing project",
                      "dsl.remote.token": "existing-secret"
                    }
                    """;
        }

        @Override
        public String commit(String jsonContent, String author, String message)
                throws IOException {
            throw new IOException("simulated Git commit failure");
        }
    }
}
