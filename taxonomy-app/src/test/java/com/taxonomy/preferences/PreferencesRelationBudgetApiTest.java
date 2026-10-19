package com.taxonomy.preferences;

import com.taxonomy.preferences.storage.PreferencesGitRepository;
import com.taxonomy.shared.controller.PreferencesController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises strict budget validation through the real preference persistence boundary. */
class PreferencesRelationBudgetApiTest {
    private PreferencesGitRepository repository;
    private PreferencesService preferences;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        repository = new PreferencesGitRepository();
        repository.commit("{\"llm.rpm\":7}", "legacy", "Existing preferences");
        preferences = new PreferencesService(repository, json);
        preferences.init();
        mvc = MockMvcBuilders.standaloneSetup(new PreferencesController(preferences)).build();
    }

    @AfterEach
    void close() throws Exception {
        repository.close();
    }

    @Test
    void olderPreferencesExposeEffectiveBudgetsWithoutWritingOnRead() throws Exception {
        String head = repository.getHistory().getFirst().commitId();
        mvc.perform(get("/api/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['analysis.relations.max-calls']").value(256))
                .andExpect(jsonPath("$['analysis.relations.max-total-calls']").value(4096))
                .andExpect(jsonPath("$['analysis.relations.auto-continue']").value(true))
                .andExpect(jsonPath("$['analysis.relations.max-sources']").value(128))
                .andExpect(jsonPath("$['analysis.relations.max-work-items']").value(4096));
        assertThat(preferences.getAll()).containsAllEntriesOf(defaults());
        assertThat(repository.getHistory()).extracting(commit -> commit.commitId()).containsExactly(head);
        assertThat(repository.readHead()).isEqualTo("{\"llm.rpm\":7}");
    }

    @Test
    void acceptedBudgetsSurviveCommitAndReload() throws Exception {
        var settings = Map.<String, Object>of(
                "analysis.relations.max-calls", 512,
                "analysis.relations.max-total-calls", 8192,
                "analysis.relations.auto-continue", false,
                "analysis.relations.max-sources", 256,
                "analysis.relations.max-work-items", 100_000);
        mvc.perform(put("/api/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(settings)))
                .andExpect(status().isOk());
        var reloaded = new PreferencesService(repository, json);
        reloaded.init();
        assertThat(reloaded.getAll()).containsAllEntriesOf(settings);
        assertThat(repository.getHistory()).hasSize(2);
    }

    @Test
    void zeroCallBudgetsAndMinimumTraversalBoundsAreAccepted() throws Exception {
        var settings = Map.<String, Object>of(
                "analysis.relations.max-calls", 0,
                "analysis.relations.max-total-calls", 0,
                "analysis.relations.max-sources", 1,
                "analysis.relations.max-work-items", 1);
        mvc.perform(put("/api/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(settings)))
                .andExpect(status().isOk());
        assertThat(preferences.getAll()).containsAllEntriesOf(settings);
    }

    @Test
    void integralJsonNumbersAreStoredAsExactIntegers() throws Exception {
        mvc.perform(put("/api/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"analysis.relations.max-calls\":512.0,\"analysis.relations.max-total-calls\":1e4}"))
                .andExpect(status().isOk());
        assertThat(preferences.get("analysis.relations.max-calls")).isEqualTo(512).isInstanceOf(Integer.class);
        assertThat(preferences.get("analysis.relations.max-total-calls")).isEqualTo(10_000).isInstanceOf(Integer.class);
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalidIntegerBudgets")
    void invalidIntegerBudgetRejectsTheWholeUpdate(String key, String invalidJson, int min, int max) throws Exception {
        String head = repository.readHead();
        var before = preferences.getAll();
        mvc.perform(put("/api/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"llm.rpm\":99,\"" + key + "\":" + invalidJson + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(key + " must be an integer between " + min + " and " + max));
        assertThat(repository.readHead()).isEqualTo(head);
        assertThat(repository.getHistory()).hasSize(1);
        assertThat(preferences.getAll()).isEqualTo(before);
    }

    private static Stream<Arguments> invalidIntegerBudgets() {
        return Stream.of(
                new Object[]{"analysis.relations.max-calls", 0, 10_000},
                new Object[]{"analysis.relations.max-total-calls", 0, 10_000},
                new Object[]{"analysis.relations.max-sources", 1, 256},
                new Object[]{"analysis.relations.max-work-items", 1, 100_000})
                .flatMap(spec -> Stream.of("1.25", "1.00000000000000000001", "2147483648", "4294967297", "1e100", "null", "true",
                                "\"256\"", "[]", "{}", Integer.toString((int) spec[1] - 1),
                                Integer.toString((int) spec[2] + 1))
                        .map(value -> Arguments.of(spec[0], value, spec[1], spec[2])));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "1", "\"true\"", "\"false\"", "\"yes\"", "[]", "{}"})
    void invalidAutomaticContinuationFlagRejectsTheWholeUpdate(String invalidJson) throws Exception {
        String head = repository.readHead();
        var before = preferences.getAll();
        mvc.perform(put("/api/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"analysis.relations.max-calls\":512,\"analysis.relations.auto-continue\":" + invalidJson + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("analysis.relations.auto-continue must be true or false"));
        assertThat(repository.readHead()).isEqualTo(head);
        assertThat(repository.getHistory()).hasSize(1);
        assertThat(preferences.getAll()).isEqualTo(before);
    }

    @Test
    void resetReturnsAndPersistsAllBudgetDefaults() throws Exception {
        preferences.update(Map.of("analysis.relations.max-calls", 0,
                "analysis.relations.max-total-calls", 0, "analysis.relations.auto-continue", false), "tester");
        mvc.perform(post("/api/preferences/reset")).andExpect(status().isOk());
        assertThat(preferences.getAll()).containsAllEntriesOf(defaults());
        var reloaded = new PreferencesService(repository, json);
        reloaded.init();
        assertThat(reloaded.getAll()).containsAllEntriesOf(defaults());
    }

    private static Map<String, Object> defaults() {
        return Map.of("analysis.relations.max-calls", 256, "analysis.relations.max-total-calls", 4096,
                "analysis.relations.auto-continue", true, "analysis.relations.max-sources", 128,
                "analysis.relations.max-work-items", 4096);
    }
}
