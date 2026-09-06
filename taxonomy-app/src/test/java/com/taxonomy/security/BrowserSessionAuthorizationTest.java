package com.taxonomy.security;

import com.taxonomy.security.controller.BrowserSessionController;
import com.taxonomy.security.service.BrowserSessionInventory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controller method-security contract, without starting the full application or database. */
@WebMvcTest(BrowserSessionController.class)
@Import({BrowserSessionController.class, BrowserSessionInventory.class, BrowserSessionAuthorizationTest.Security.class})
class BrowserSessionAuthorizationTest {
    @Autowired MockMvc mvc;

    @Test
    @WithMockUser(roles = "ADMIN")
    void administratorGetsOnlyTheNoStoreProjection() throws Exception {
        mvc.perform(get("/api/admin/sessions"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.scope").value("LOCAL_INSTANCE"))
                .andExpect(jsonPath("$.users").isArray());
    }

    @Test
    @WithMockUser(roles = "USER")
    void ordinaryUserCannotReadEitherRepresentation() throws Exception {
        mvc.perform(get("/api/admin/sessions")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/sessions")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotReadTheInventory() throws Exception {
        mvc.perform(get("/api/admin/sessions")).andExpect(status().isUnauthorized());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security {
        @Bean SessionRegistry registry() { return new SessionRegistryImpl(); }
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            // Deliberately no URL role check: these tests prove the controller's
            // method authorization independently of production's ADMIN URL rules.
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults()).build();
        }
    }
}
