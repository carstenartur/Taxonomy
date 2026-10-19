package com.taxonomy.preferences;

import com.taxonomy.preferences.storage.PreferencesGitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link PreferencesService} with an in-memory JGit repository.
 *
 * <p>Loads only the service and its real in-memory dependencies so that
 * {@code @Value} property injection is tested without initializing the application,
 * database, catalogue, or search indexes.
 */
@SpringJUnitConfig(PreferencesServiceTest.InMemoryPreferencesConfig.class)
@TestPropertySource(properties = {
    "gemini.api.key=",
    "openai.api.key=",
    "deepseek.api.key=",
    "qwen.api.key=",
    "llama.api.key=",
    "mistral.api.key=",
    "taxonomy.llm.rpm=7",
    "taxonomy.llm.timeout-seconds=45",
    "taxonomy.rate-limit.per-minute=12",
    "taxonomy.limits.max-business-text=9000",
    "taxonomy.analysis.relations.hierarchical.max-calls=320",
    "taxonomy.analysis.relations.hierarchical.max-total-calls=2400",
    "taxonomy.analysis.relations.hierarchical.auto-continue=false",
    "taxonomy.analysis.relations.hierarchical.max-sources=96",
    "taxonomy.analysis.relations.hierarchical.max-work-items=8192"
})
@WithMockUser(roles = "ADMIN")
class PreferencesServiceTest {

    static final class ControllablePreferencesGitRepository extends PreferencesGitRepository {
        private final AtomicBoolean failNextCommit = new AtomicBoolean();

        void failNextCommit() {
            failNextCommit.set(true);
        }

        @Override
        public String commit(String jsonContent, String author, String message) throws IOException {
            if (failNextCommit.getAndSet(false)) {
                throw new IOException("simulated preferences commit failure");
            }
            return super.commit(jsonContent, author, message);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(PreferencesService.class)
    static class InMemoryPreferencesConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        public ControllablePreferencesGitRepository testPreferencesGitRepository() {
            return new ControllablePreferencesGitRepository();
        }
    }

    @Autowired
    private PreferencesService preferencesService;

    @Autowired
    private ControllablePreferencesGitRepository preferencesRepository;

    @BeforeEach
    void reset() throws IOException {
        // Reset to defaults before each test to avoid cross-test interference
        preferencesService.resetToDefaults("test");
    }

    @Test
    void defaultsAreLoadedFromProperties() {
        assertThat(preferencesService.getInt("llm.rpm", 0)).isEqualTo(7);
        assertThat(preferencesService.getInt("llm.timeout.seconds", 0)).isEqualTo(45);
        assertThat(preferencesService.getInt("rate-limit.per-minute", 0)).isEqualTo(12);
        assertThat(preferencesService.getInt("limits.max-business-text", 0)).isEqualTo(9000);
        assertThat(preferencesService.getString("analysis.reason-length", "missing")).isEqualTo("standard");
        assertThat(preferencesService.getAll()).containsAllEntriesOf(Map.of(
                "analysis.relations.max-calls", 320,
                "analysis.relations.max-total-calls", 2400,
                "analysis.relations.auto-continue", false,
                "analysis.relations.max-sources", 96,
                "analysis.relations.max-work-items", 8192));
    }

    @Test
    void legacyPreferencesKeepOverridesAndExposeOtherDeploymentBudgetsWithoutACommit() throws IOException {
        preferencesRepository.commit("{\"analysis.relations.max-calls\":77}", "legacy", "Older settings");
        int historyBefore = preferencesRepository.getHistory().size();
        preferencesService.init();

        assertThat(preferencesService.getAll()).containsAllEntriesOf(Map.of(
                "analysis.relations.max-calls", 77,
                "analysis.relations.max-total-calls", 2400,
                "analysis.relations.auto-continue", false,
                "analysis.relations.max-sources", 96,
                "analysis.relations.max-work-items", 8192));
        assertThat(preferencesRepository.getHistory()).hasSize(historyBefore);
        assertThat(preferencesRepository.readHead()).isEqualTo("{\"analysis.relations.max-calls\":77}");
    }

    @Test
    void getAllReturnsMaskedToken() throws IOException {
        preferencesService.update(Map.of("dsl.remote.token", "mySecret1234"), "test");
        Map<String, Object> all = preferencesService.getAll();
        Object token = all.get("dsl.remote.token");
        assertThat(token).isNotNull();
        assertThat(token.toString()).startsWith("****");
        assertThat(token.toString()).doesNotContain("mySecret");
    }

    @Test
    void getAllDoesNotMaskEmptyToken() {
        Map<String, Object> all = preferencesService.getAll();
        Object token = all.get("dsl.remote.token");
        // empty string stays empty (not masked)
        assertThat(token).isEqualTo("");
    }

    @Test
    void updateMergesChanges() throws IOException {
        preferencesService.update(Map.of("llm.rpm", 3), "tester");
        assertThat(preferencesService.getInt("llm.rpm", 0)).isEqualTo(3);
        // Other keys should remain unchanged
        assertThat(preferencesService.getInt("llm.timeout.seconds", 0)).isEqualTo(45);
    }

    @Test
    void failedUpdateDoesNotLeakUncommittedRuntimePreferences() throws IOException {
        int before = preferencesService.getInt("limits.max-architecture-nodes", 0);
        int historyBefore = preferencesService.getHistory().size();

        preferencesRepository.failNextCommit();

        assertThatThrownBy(() -> preferencesService.update(
                Map.of("limits.max-architecture-nodes", before + 100), "tester"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated preferences commit failure");

        assertThat(preferencesService.getInt("limits.max-architecture-nodes", 0))
                .isEqualTo(before);
        assertThat(preferencesService.getHistory()).hasSize(historyBefore);
    }

    @Test
    void failedBudgetCommitDoesNotChangeAnyEffectivePreference() throws IOException {
        var before = preferencesService.getAll();
        int historyBefore = preferencesService.getHistory().size();
        preferencesRepository.failNextCommit();

        assertThatThrownBy(() -> preferencesService.update(Map.of(
                "analysis.relations.max-calls", 1024,
                "analysis.relations.max-total-calls", 8192,
                "analysis.relations.auto-continue", true,
                "analysis.relations.max-sources", 256,
                "analysis.relations.max-work-items", 100_000), "tester"))
                .isInstanceOf(IOException.class);

        assertThat(preferencesService.getAll()).isEqualTo(before);
        assertThat(preferencesService.getHistory()).hasSize(historyBefore);
    }

    @Test
    void failedResetDoesNotReplaceActiveRuntimePreferences() throws IOException {
        preferencesService.update(Map.of("llm.rpm", 99), "tester");
        int historyBefore = preferencesService.getHistory().size();

        preferencesRepository.failNextCommit();

        assertThatThrownBy(() -> preferencesService.resetToDefaults("tester"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated preferences commit failure");

        assertThat(preferencesService.getInt("llm.rpm", 0)).isEqualTo(99);
        assertThat(preferencesService.getHistory()).hasSize(historyBefore);
    }

    @Test
    void historyGrowsWithEachUpdate() throws IOException {
        int before = preferencesService.getHistory().size();
        preferencesService.update(Map.of("llm.rpm", 2), "tester");
        preferencesService.update(Map.of("llm.rpm", 4), "tester");
        assertThat(preferencesService.getHistory().size()).isEqualTo(before + 2);
    }

    @Test
    void resetToDefaultsRestoresPropertyValues() throws IOException {
        preferencesService.update(Map.of("llm.rpm", 99, "analysis.reason-length", "detailed"), "tester");
        preferencesService.resetToDefaults("tester");
        assertThat(preferencesService.getInt("llm.rpm", 0)).isEqualTo(7); // from test property
        assertThat(preferencesService.getString("analysis.reason-length", "missing")).isEqualTo("standard");
    }

    @Test
    void getStringReturnsDefault() {
        String branch = preferencesService.getString("dsl.default-branch", "main");
        assertThat(branch).isEqualTo("draft"); // from application.properties default
    }

    @Test
    void getBooleanReturnsDefault() {
        boolean push = preferencesService.getBoolean("dsl.remote.push-on-commit", true);
        assertThat(push).isFalse(); // default is false
    }
}
