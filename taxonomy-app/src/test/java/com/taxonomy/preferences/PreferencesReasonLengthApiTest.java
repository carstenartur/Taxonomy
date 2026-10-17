package com.taxonomy.preferences;

import com.taxonomy.dto.AnalysisReasonLength;
import com.taxonomy.preferences.storage.PreferencesGitRepository;
import com.taxonomy.shared.controller.PreferencesController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real preference persistence behind the HTTP boundary, without starting any model or catalogue. */
class PreferencesReasonLengthApiTest {
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
    void olderSettingsExposeTheEffectiveStandardWithoutWritingOnRead() throws Exception {
        String head = repository.getHistory().getFirst().commitId();
        mvc.perform(get("/api/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['analysis.reason-length']").value("standard"));
        assertThat(preferences.getString("analysis.reason-length", "missing")).isEqualTo("standard");
        assertThat(repository.getHistory()).extracting(commit -> commit.commitId()).containsExactly(head);
        assertThat(repository.readHead()).isEqualTo("{\"llm.rpm\":7}");
    }

    @Test
    void olderSettingsUseTheConfiguredDeploymentDefault() throws Exception {
        var configured = new PreferencesService(repository, json);
        ReflectionTestUtils.setField(configured, "defaultAnalysisReasonLength", "detailed");
        configured.init();
        assertThat(configured.getAll()).containsEntry("analysis.reason-length", "detailed");
        assertThat(repository.readHead()).isEqualTo("{\"llm.rpm\":7}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "standard", "detailed"})
    void acceptedValueSurvivesCommitAndServiceReload(String value) throws Exception {
        mvc.perform(put("/api/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("analysis.reason-length", value))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['analysis.reason-length']").value(value));
        assertThat(json.readTree(repository.readHead()).path("analysis.reason-length").asText()).isEqualTo(value);
        var reloaded = new PreferencesService(repository, json);
        reloaded.init();
        assertThat(reloaded.getString("analysis.reason-length", "missing")).isEqualTo(value);
        assertThat(repository.getHistory()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "17", "[]", "{}", "\"\"", "\" \"", "\"long\"", "\"DETAILED\""})
    void invalidValueReturnsBadRequestWithoutAnyPartialCommit(String invalidJson) throws Exception {
        String head = repository.readHead();
        mvc.perform(put("/api/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"llm.rpm\":99,\"analysis.reason-length\":" + invalidJson + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("analysis.reason-length must be short, standard or detailed"));
        assertThat(repository.readHead()).isEqualTo(head);
        assertThat(repository.getHistory()).hasSize(1);
        assertThat(preferences.getInt("llm.rpm", 0)).isEqualTo(7);
        assertThat(preferences.getString("analysis.reason-length", "missing")).isEqualTo("standard");
    }

    @Test
    void resetReturnsAndPersistsTheConfiguredDefault() throws Exception {
        preferences.update(java.util.Map.of("analysis.reason-length", "detailed"), "tester");
        mvc.perform(post("/api/preferences/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['analysis.reason-length']").value("standard"));
        assertThat(json.readTree(repository.readHead()).path("analysis.reason-length").asText()).isEqualTo("standard");
    }

    @Test
    void enumUsesLowerCaseWireValuesWithTheApplicationMapper() throws Exception {
        for (var value : AnalysisReasonLength.values()) {
            String serialized = json.writeValueAsString(value);
            assertThat(serialized).isEqualTo('"' + value.value() + '"');
            assertThat(json.readValue(serialized, AnalysisReasonLength.class)).isEqualTo(value);
        }
        assertThat(json.readValue("null", AnalysisReasonLength.class)).isNull();
        for (String blank : new String[]{"", " ", "\t", "\r\n"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> json.readValue(json.writeValueAsString(blank), AnalysisReasonLength.class))
                    .isInstanceOf(tools.jackson.core.JacksonException.class);
        }
    }
}
