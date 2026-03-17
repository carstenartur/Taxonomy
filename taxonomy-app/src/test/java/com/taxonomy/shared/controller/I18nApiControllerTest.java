package com.taxonomy.shared.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link I18nApiController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "USER")
class I18nApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void englishLocaleReturnsOk() throws Exception {
        mockMvc.perform(get("/api/i18n/en").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(greaterThan(100))));
    }

    @Test
    void englishLocaleContainsExpectedKeys() throws Exception {
        mockMvc.perform(get("/api/i18n/en").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['app.title']").value("Taxonomy Architecture Analyzer"))
                .andExpect(jsonPath("$.['nav.analyze']").value("Analyze"))
                .andExpect(jsonPath("$.['nav.help']").value("Help"))
                .andExpect(jsonPath("$.['lang.selector.label']").value("Language"));
    }

    @Test
    void germanLocaleReturnsTranslatedValues() throws Exception {
        mockMvc.perform(get("/api/i18n/de").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['app.title']").value("Taxonomie Architektur Analysator"))
                .andExpect(jsonPath("$.['nav.analyze']").value("Analyse"))
                .andExpect(jsonPath("$.['nav.help']").value("Hilfe"))
                .andExpect(jsonPath("$.['lang.selector.label']").value("Sprache"));
    }

    @Test
    void unknownLocaleFallsBackToEnglish() throws Exception {
        mockMvc.perform(get("/api/i18n/fr").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['app.title']").value("Taxonomy Architecture Analyzer"))
                .andExpect(jsonPath("$.['nav.analyze']").value("Analyze"));
    }

    @Test
    void englishAndGermanHaveSameKeyCount() throws Exception {
        // Both locales must return the same number of keys
        mockMvc.perform(get("/api/i18n/en").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(359)));

        mockMvc.perform(get("/api/i18n/de").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(359)));
    }
}
