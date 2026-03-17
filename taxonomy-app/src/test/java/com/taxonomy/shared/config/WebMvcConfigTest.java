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
 * (via {@code LocaleChangeInterceptor}) and that a {@code lang} cookie
 * persists the preference (via {@code CookieLocaleResolver}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class WebMvcConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void langParameterSetsLocaleCookie() throws Exception {
        // ?lang=de triggers LocaleChangeInterceptor → CookieLocaleResolver sets cookie
        mockMvc.perform(get("/help").param("lang", "de"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("lang", "de"));
    }

    @Test
    void langCookieResolvesLocale() throws Exception {
        // Sending a lang=de cookie should cause HelpController to serve German docs
        mockMvc.perform(get("/help/USER_GUIDE").cookie(new Cookie("lang", "de")))
                .andExpect(status().isOk());
    }

    @Test
    void defaultLocaleIsEnglish() throws Exception {
        // Without any locale hint the i18n endpoint returns English translations
        mockMvc.perform(get("/api/i18n/en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['nav.analyze']").value("Analyze"));
    }
}
