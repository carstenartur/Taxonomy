package com.taxonomy.shared.config;

import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link WebMvcConfig} locale resolution and request-bound
 * workspace pre-resolution.
 *
 * <p>Verifies locale persistence, initial workspace provisioning, and the global
 * fail-closed boundary for explicit browser-tab workspace pins.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class WebMvcConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserWorkspaceRepository workspaceRepository;

    @Test
    void langParameterSetsLocaleCookie() throws Exception {
        mockMvc.perform(get("/help").param("lang", "de")
                        .accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("lang", "de"))
                .andExpect(jsonPath("$[0].title").value("Benutzerhandbuch"));
    }

    @Test
    void langCookieResolvesLocale() throws Exception {
        mockMvc.perform(get("/help").cookie(new Cookie("lang", "de"))
                        .accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Benutzerhandbuch"));
    }

    @Test
    void defaultLocaleIsEnglish() throws Exception {
        mockMvc.perform(get("/help").accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("User Guide"));
    }

    @Test
    void firstPortfolioApiRequestPreResolvesAnIsolatedWorkspace() throws Exception {
        mockMvc.perform(get("/api/projects").accept("application/json"))
                .andExpect(status().isOk());

        assertThat(workspaceRepository.findByUsernameAndSharedFalse("user"))
                .isPresent();
    }

    @Test
    void foreignHeaderPinIsRejectedBeforeAnOtherwiseUnscopedApiRuns() throws Exception {
        mockMvc.perform(get("/api/embedding/status")
                        .header(
                                WorkspaceContextResolver.WORKSPACE_HEADER,
                                "missing-or-foreign-workspace")
                        .accept("application/json"))
                .andExpect(status().isForbidden());
    }

    @Test
    void foreignQueryPinUsesTheSameGlobalAuthorizationBoundary() throws Exception {
        mockMvc.perform(get("/api/embedding/status")
                        .param(
                                WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER,
                                "missing-or-foreign-workspace")
                        .accept("application/json"))
                .andExpect(status().isForbidden());
    }
}
