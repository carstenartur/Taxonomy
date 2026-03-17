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
        // AND the response should already reflect the German locale.
        mockMvc.perform(get("/help").param("lang", "de")
                        .accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("lang", "de"))
                .andExpect(jsonPath("$[0].title").value("Benutzerhandbuch"));
    }

    @Test
    void langCookieResolvesLocale() throws Exception {
        // Sending a lang=de cookie should cause HelpController TOC to return
        // German-translated titles (resolved via CookieLocaleResolver → MessageSource).
        mockMvc.perform(get("/help").cookie(new Cookie("lang", "de"))
                        .accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Benutzerhandbuch"));
    }

    @Test
    void defaultLocaleIsEnglish() throws Exception {
        // Without any cookie or ?lang= parameter, LocaleContextHolder should
        // resolve to English.  We verify via /help TOC (which uses MessageSource
        // with the resolved locale) — the first entry title must be English.
        mockMvc.perform(get("/help").accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("User Guide"));
    }
}
