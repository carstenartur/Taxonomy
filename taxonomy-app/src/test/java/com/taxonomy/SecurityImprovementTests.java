package com.taxonomy;

import com.taxonomy.security.config.LoginRateLimitFilter;
import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.RoleRepository;
import com.taxonomy.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests for rate limiting, security headers, user management and password change. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "gemini.api.key=",
    "openai.api.key=",
    "deepseek.api.key=",
    "qwen.api.key=",
    "llama.api.key=",
    "mistral.api.key=",
    "taxonomy.security.login-rate-limit.enabled=true",
    "taxonomy.security.login-rate-limit.max-attempts=3",
    "taxonomy.security.login-rate-limit.lockout-seconds=60"
})
class SecurityImprovementTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired(required = false)
    private LoginRateLimitFilter loginRateLimitFilter;

    @BeforeEach
    void resetLoginRateLimitCounters() {
        if (loginRateLimitFilter != null) {
            loginRateLimitFilter.clearTrackers();
        }
    }

    @Test
    void loginRateLimitFilterBeanIsLoaded() {
        assertThat(loginRateLimitFilter).isNotNull();
    }

    @Test
    void loginRateLimitFilterTrackerIsInitiallyEmpty() {
        assertThat(loginRateLimitFilter.getTrackers()).isEmpty();
    }

    @Test
    void authenticatedUserIsNotBlockedByLockout() throws Exception {
        assertThat(loginRateLimitFilter)
                .as("LoginRateLimitFilter bean must be present")
                .isNotNull();

        int maxAttempts = (int) ReflectionTestUtils.getField(
                loginRateLimitFilter, "maxAttempts");
        for (int index = 0; index < maxAttempts; index++) {
            ReflectionTestUtils.invokeMethod(
                    loginRateLimitFilter, "recordFailure", "10.99.99.99");
        }
        assertThat(loginRateLimitFilter.getTrackers()).containsKey("10.99.99.99");

        mockMvc.perform(get("/api/taxonomy")
                        .accept(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "10.99.99.99"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/taxonomy")
                        .accept(MediaType.APPLICATION_JSON)
                        .with(user("admin").roles("USER"))
                        .header("X-Forwarded-For", "10.99.99.99"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void responseContainsXContentTypeOptionsHeader() throws Exception {
        mockMvc.perform(get("/api/taxonomy").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void responseContainsXFrameOptionsHeader() throws Exception {
        mockMvc.perform(get("/api/taxonomy").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void responseContainsReferrerPolicyHeader() throws Exception {
        mockMvc.perform(get("/api/taxonomy").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    @WithAnonymousUser
    void changePasswordPageRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/change-password"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void changePasswordPageIsAccessible() throws Exception {
        mockMvc.perform(get("/change-password"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanListUsers() throws Exception {
        mockMvc.perform(get("/api/admin/users").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].username").isString())
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotAccessUserManagement() throws Exception {
        mockMvc.perform(get("/api/admin/users").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ARCHITECT")
    void architectCannotAccessUserManagement() throws Exception {
        mockMvc.perform(get("/api/admin/users").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCreateUser() throws Exception {
        userRepository.findByUsername("testuser").ifPresent(userRepository::delete);

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"testpass123\","
                                + "\"roles\":[\"USER\"],\"displayName\":\"Test User\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.displayName").value("Test User"))
                .andExpect(jsonPath("$.enabled").value(true));

        Optional<AppUser> user = userRepository.findByUsername("testuser");
        assertThat(user).isPresent();
        assertThat(user.get().isEnabled()).isTrue();
        userRepository.delete(user.get());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createUserRejectsDuplicateUsername() throws Exception {
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"testpass123\","
                                + "\"roles\":[\"USER\"]}"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createUserRejectsShortPassword() throws Exception {
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shortpw\",\"password\":\"short\","
                                + "\"roles\":[\"USER\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cannotDisableLastAdmin() throws Exception {
        AppUser admin = userRepository.findByUsername("admin").orElseThrow();
        mockMvc.perform(delete("/api/admin/users/" + admin.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Cannot disable the last admin user."));
    }

    @Test
    void bootstrapAdminHasNoKnownDefaultPassword() {
        Optional<AppUser> admin = userRepository.findByUsername("admin");
        assertThat(admin).isPresent();
        assertThat(admin.get().isMustChangePassword()).isTrue();
        assertThat(passwordEncoder.matches("admin", admin.get().getPasswordHash())).isFalse();
    }
}
