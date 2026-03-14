package com.taxonomy.preferences;

import com.taxonomy.preferences.storage.PreferencesGitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link PreferencesService} with an in-memory JGit repository.
 *
 * <p>Uses a full Spring context so that {@code @Value} property injection is tested.
 * The {@link PreferencesGitRepository} is replaced with an in-memory instance to
 * avoid requiring a database.
 */
@SpringBootTest
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
    "taxonomy.limits.max-business-text=9000"
})
@WithMockUser(roles = "ADMIN")
class PreferencesServiceTest {

    @TestConfiguration
    static class InMemoryPreferencesConfig {
        @Bean
        @Primary
        public PreferencesGitRepository testPreferencesGitRepository() {
            return new PreferencesGitRepository(); // in-memory, no DB
        }
    }

    @Autowired
    private PreferencesService preferencesService;

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
    void historyGrowsWithEachUpdate() throws IOException {
        int before = preferencesService.getHistory().size();
        preferencesService.update(Map.of("llm.rpm", 2), "tester");
        preferencesService.update(Map.of("llm.rpm", 4), "tester");
        assertThat(preferencesService.getHistory().size()).isEqualTo(before + 2);
    }

    @Test
    void resetToDefaultsRestoresPropertyValues() throws IOException {
        preferencesService.update(Map.of("llm.rpm", 99), "tester");
        preferencesService.resetToDefaults("tester");
        assertThat(preferencesService.getInt("llm.rpm", 0)).isEqualTo(7); // from test property
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
