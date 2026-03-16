package com.taxonomy;

import com.taxonomy.security.config.SecurityConfig;
import com.taxonomy.security.config.SecurityDataInitializer;
import com.taxonomy.security.model.AppRole;
import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.RoleRepository;
import com.taxonomy.security.repository.UserRepository;
import com.taxonomy.security.service.DatabaseUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for Spring Security configuration: authentication, authorization,
 * role-based access control, and database-backed user management.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "gemini.api.key=",
    "openai.api.key=",
    "deepseek.api.key=",
    "qwen.api.key=",
    "llama.api.key=",
    "mistral.api.key="
})
class SecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ── Data Initializer ──────────────────────────────────────────────────────

    @Test
    void defaultRolesAreCreated() {
        assertThat(roleRepository.findByName("ROLE_USER")).isPresent();
        assertThat(roleRepository.findByName("ROLE_ARCHITECT")).isPresent();
        assertThat(roleRepository.findByName("ROLE_ADMIN")).isPresent();
    }

    @Test
    void defaultAdminUserIsCreated() {
        Optional<AppUser> admin = userRepository.findByUsername("admin");
        assertThat(admin).isPresent();
        assertThat(admin.get().isEnabled()).isTrue();
        assertThat(admin.get().getRoles()).extracting(AppRole::getName)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ARCHITECT", "ROLE_ADMIN");
        assertThat(passwordEncoder.matches("admin", admin.get().getPasswordHash())).isTrue();
    }

    // ── Beans ─────────────────────────────────────────────────────────────────

    @Test
    void securityConfigBeanIsLoaded(@Autowired SecurityConfig config) {
        assertThat(config).isNotNull();
    }

    @Test
    void databaseUserDetailsServiceBeanIsLoaded(@Autowired DatabaseUserDetailsService service) {
        assertThat(service).isNotNull();
    }

    @Test
    void securityDataInitializerBeanIsLoaded(@Autowired SecurityDataInitializer initializer) {
        assertThat(initializer).isNotNull();
    }

    // ── Public endpoints ──────────────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    void loginPageIsPublic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    void swaggerUiIsPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    // ── Unauthenticated access is denied ─────────────────────────────────────

    @Test
    @WithAnonymousUser
    void unauthenticatedApiAccessIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/taxonomy"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithAnonymousUser
    void unauthenticatedGuiAccessIsUnauthorized() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isUnauthorized());
    }

    // ── Authenticated USER access ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "USER")
    void authenticatedUserCanAccessTaxonomyApi() throws Exception {
        mockMvc.perform(get("/api/taxonomy").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void authenticatedUserCanAccessGui() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    // ── Role-based access: USER cannot write relations ────────────────────────

    @Test
    @WithMockUser(roles = "USER")
    void userCannotPostRelations() throws Exception {
        mockMvc.perform(post("/api/relations/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotPostDsl() throws Exception {
        mockMvc.perform(post("/api/dsl/parse")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("meta { }"))
                .andExpect(status().isForbidden());
    }

    // ── Role-based access: ARCHITECT can write relations ──────────────────────

    @Test
    @WithMockUser(roles = "ARCHITECT")
    void architectCanPostDsl() throws Exception {
        mockMvc.perform(post("/api/dsl/parse")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("meta { }"))
                .andExpect(status().isOk());
    }

    // ── Role-based access: Admin-only endpoints ───────────────────────────────

    @Test
    @WithMockUser(roles = "USER")
    void userCannotAccessAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/status"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ARCHITECT")
    void architectCannotAccessAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/status"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanAccessAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/status"))
                .andExpect(status().isOk());
    }

    // ── Password encoding ─────────────────────────────────────────────────────

    @Test
    void passwordEncoderIsBCrypt() {
        assertThat(passwordEncoder).isInstanceOf(
                org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder.class);
    }
}
