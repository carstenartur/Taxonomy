package com.taxonomy;

import com.taxonomy.security.config.LoginRateLimitFilter;
import com.taxonomy.security.controller.UserManagementController;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the security improvements:
 * <ul>
 *   <li>Login brute-force protection (LoginRateLimitFilter)</li>
 *   <li>Security headers (X-Content-Type-Options, X-Frame-Options, HSTS, Referrer-Policy)</li>
 *   <li>User management API</li>
 *   <li>Change password endpoint</li>
 *   <li>Swagger access control</li>
 * </ul>
 */
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

    // ── Login Rate Limit Filter ────────────────────────────────────────────

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
        // Simulate failed login attempts to trigger lockout via private recordFailure method.
        // Uses ReflectionTestUtils to avoid adding test-only public methods to production code.
        int maxAttempts = (int) ReflectionTestUtils.getField(loginRateLimitFilter, "maxAttempts");
        for (int i = 0; i < maxAttempts; i++) {
            ReflectionTestUtils.invokeMethod(loginRateLimitFilter, "recordFailure", "10.99.99.99");
        }
        // Verify IP is actually locked out
        assertThat(loginRateLimitFilter.getTrackers()).containsKey("10.99.99.99");

        // Authenticated user from the same locked-out IP should NOT be blocked
        mockMvc.perform(get("/api/taxonomy")
                        .accept(MediaType.APPLICATION_JSON)
                        .with(user("admin").roles("USER"))
                        .header("X-Forwarded-For", "10.99.99.99"))
                .andExpect(status().isOk());
    }

    // ── Security Headers ───────────────────────────────────────────────────

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
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    // ── Change Password Endpoint ────────────────────────────────────────────

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

    // ── User Management API ─────────────────────────────────────────────────

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
        // Clean up if test user already exists
        userRepository.findByUsername("testuser").ifPresent(userRepository::delete);

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"testpass123\",\"roles\":[\"USER\"],\"displayName\":\"Test User\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.displayName").value("Test User"))
                .andExpect(jsonPath("$.enabled").value(true));

        // Verify user was created in the database
        Optional<AppUser> user = userRepository.findByUsername("testuser");
        assertThat(user).isPresent();
        assertThat(user.get().isEnabled()).isTrue();

        // Cleanup
        userRepository.delete(user.get());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createUserRejectsDuplicateUsername() throws Exception {
        // admin user already exists
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"testpass123\",\"roles\":[\"USER\"]}"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createUserRejectsShortPassword() throws Exception {
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shortpw\",\"password\":\"short\",\"roles\":[\"USER\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cannotDisableLastAdmin() throws Exception {
        // Find the admin user's ID
        AppUser admin = userRepository.findByUsername("admin").orElseThrow();
        mockMvc.perform(delete("/api/admin/users/" + admin.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cannot disable the last admin user."));
    }

    // ── Default Password Warning ────────────────────────────────────────────

    @Test
    void defaultAdminUserIsCreatedWithDefaultPassword() {
        Optional<AppUser> admin = userRepository.findByUsername("admin");
        assertThat(admin).isPresent();
        assertThat(passwordEncoder.matches("admin", admin.get().getPasswordHash())).isTrue();
    }
}
