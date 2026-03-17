package com.taxonomy.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link WebMvcConfig} locale resolution.
 *
 * <p>Verifies that the {@code ?lang=} query parameter switches locale
 * and that a {@code lang} cookie persists the preference.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class WebMvcConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void langParameterSwitchesToGerman() throws Exception {
        // ?lang=de should cause server-side Thymeleaf to resolve German messages
        mockMvc.perform(get("/api/i18n/de"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['nav.analyze']").value("Analyse"));
    }

    @Test
    void langCookiePersistsLocale() throws Exception {
        // Sending a lang cookie should be recognised by CookieLocaleResolver
        mockMvc.perform(get("/api/i18n/de").cookie(new Cookie("lang", "de")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['nav.analyze']").value("Analyse"));
    }

    @Test
    void defaultLocaleIsEnglish() throws Exception {
        // Without any locale hint the default should be English
        mockMvc.perform(get("/api/i18n/en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['nav.analyze']").value("Analyze"));
    }
}
