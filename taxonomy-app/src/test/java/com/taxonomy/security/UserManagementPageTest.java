package com.taxonomy.security;

import com.taxonomy.security.controller.UserManagementPageController;
import com.taxonomy.security.service.UserManagementService;
import com.taxonomy.shared.config.I18nConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserManagementPageController.class)
@ActiveProfiles("local-user-management")
@Import({UserManagementPageController.class, I18nConfig.class, UserManagementPageTest.Security.class})
class UserManagementPageTest {
    private static final String PASSWORD = "Temporary-test-password-123";
    private static final Map<String, Object> ACCOUNT = Map.of(
            "id", 7L, "username", "alice", "displayName", "Alice", "email", "alice@example.test",
            "enabled", true, "mustChangePassword", false, "roles", List.of("ROLE_USER"));
    @Autowired WebApplicationContext context;
    @MockitoBean UserManagementService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listRendersTheGermanPageWithoutCachingOrPasswordHashes() throws Exception {
        when(service.listUsers()).thenReturn(List.of(ACCOUNT));
        mvc.perform(get("/admin/users").param("lang", "de").locale(java.util.Locale.GERMAN))
                .andExpect(status().isOk()).andExpect(view().name("user-management"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("alice")))
                .andExpect(content().string(not(containsString("passwordHash"))));
    }

    @Test
    void anonymousCannotAccessThePage() throws Exception {
        mvc.perform(get("/admin/users")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(roles = "ARCHITECT")
    void architectCannotReadOrModifyAccounts() throws Exception {
        mvc.perform(get("/admin/users")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/7/status").param("enabled", "false").with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void csrfIsRequiredEvenForAnAdministrator() throws Exception {
        mvc.perform(post("/admin/users/7/status").param("enabled", "false"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "operator", roles = "ADMIN")
    void createsAnAccountThroughTheExistingServiceAndRedirectsWithoutSecrets() throws Exception {
        when(service.createUser(any(), eq(PASSWORD), eq("operator"))).thenReturn(ACCOUNT);
        var result = mvc.perform(post("/admin/users").with(csrf())
                        .param("username", "alice").param("displayName", "Alice")
                        .param("email", "alice@example.test").param("roles", "ROLE_USER")
                        .param("newPassword", PASSWORD).param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successKey", "users.created")).andReturn();
        verify(service).createUser(Map.of("username", "alice", "displayName", "Alice",
                "email", "alice@example.test", "roles", List.of("ROLE_USER")), PASSWORD, "operator");
        verify(service, never()).createUser(any(), any());
        assertThat(result.getFlashMap().toString()).doesNotContain(PASSWORD);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void mismatchedPasswordsNeverReachTheServiceOrReturnInHtml() throws Exception {
        mvc.perform(post("/admin/users").with(csrf()).param("username", "alice")
                        .param("roles", "ROLE_USER").param("newPassword", PASSWORD)
                        .param("confirmPassword", "another-test-password"))
                .andExpect(status().isBadRequest())
                .andExpect(model().attribute("errorKey", "users.error.passwordMismatch"))
                .andExpect(content().string(not(containsString(PASSWORD))));
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void validatesProfileFieldsAndUnknownRolesOnTheServer() throws Exception {
        mvc.perform(post("/admin/users").with(csrf()).param("username", "alice")
                        .param("email", "invalid-address").param("roles", "ROLE_ROOT")
                        .param("newPassword", PASSWORD).param("confirmPassword", PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(model().attributeHasFieldErrors("form", "email", "roles[0]"));
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicateUserIsAVisibleConflictWithoutEchoingPasswords() throws Exception {
        when(service.createUser(any(), any(), any())).thenThrow(
                new UserManagementService.ConflictException("Username already exists."));
        mvc.perform(post("/admin/users").with(csrf()).param("username", "alice")
                        .param("roles", "ROLE_USER").param("newPassword", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(model().attribute("errorKey", "users.error.duplicate"))
                .andExpect(content().string(not(containsString(PASSWORD))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersContextRelativeFormsAndEscapesAccountData() throws Exception {
        when(service.getUser(7L)).thenReturn(Optional.of(Map.of(
                "id", 7L, "username", "alice", "displayName", "<script>unsafe()</script>",
                "email", "", "enabled", true, "roles", List.of("ROLE_USER"))));
        mvc.perform(get("/taxonomy/admin/users/7").contextPath("/taxonomy"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/taxonomy/admin/users/7/password")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("<script>unsafe()</script>"))));
    }

    @Test
    @WithMockUser(username = "operator", roles = "ADMIN")
    void updatesRolesAndProfileButNotTheImmutableUsername() throws Exception {
        when(service.getUser(7L)).thenReturn(Optional.of(ACCOUNT));
        mvc.perform(post("/admin/users/7").with(csrf()).param("username", "tampered")
                        .param("displayName", "Alice Updated").param("email", "new@example.test")
                        .param("roles", "ROLE_USER", "ROLE_ARCHITECT"))
                .andExpect(status().is3xxRedirection());
        verify(service).updateUser(7L, Map.of("displayName", "Alice Updated",
                "email", "new@example.test", "roles", List.of("ROLE_USER", "ROLE_ARCHITECT")), "operator");
    }

    @Test
    @WithMockUser(username = "operator", roles = "ADMIN")
    void passwordResetDelegatesToTheExistingPolicy() throws Exception {
        mvc.perform(post("/admin/users/7/password").with(csrf())
                        .param("newPassword", PASSWORD).param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successKey", "users.passwordChanged"));
        verify(service).changePassword(7L, PASSWORD, "operator");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void confirmationPageDoesNotChangeTheAccount() throws Exception {
        when(service.getUser(7L)).thenReturn(Optional.of(ACCOUNT));
        mvc.perform(get("/admin/users/7/status").param("enabled", "false"))
                .andExpect(status().isOk()).andExpect(view().name("user-management-status"));
        verify(service, never()).disableUser(any(), any());
        verify(service, never()).updateUser(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void lastAdministratorProtectionRemainsServerSide() throws Exception {
        when(service.getUser(7L)).thenReturn(Optional.of(ACCOUNT));
        when(service.disableUser(eq(7L), any())).thenThrow(
                new UserManagementService.ValidationException("Cannot disable the last admin user."));
        mvc.perform(post("/admin/users/7/status").with(csrf()).param("enabled", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(model().attribute("errorKey", "users.error.lastAdmin"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void missingAccountReturnsNotFound() throws Exception {
        when(service.getUser(99L)).thenReturn(Optional.empty());
        mvc.perform(get("/admin/users/99")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "operator", roles = "ADMIN")
    void enablingAndDisablingUseExplicitPostActions() throws Exception {
        mvc.perform(post("/admin/users/7/status").with(csrf()).param("enabled", "true"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("successKey", "users.enabled"));
        verify(service).updateUser(7L, Map.of("enabled", true), "operator");
        mvc.perform(post("/admin/users/7/status").with(csrf()).param("enabled", "false"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("successKey", "users.disabled"));
        verify(service).disableUser(7L, "operator");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void invalidEditIsRenderedWithoutCallingTheUpdateService() throws Exception {
        when(service.getUser(7L)).thenReturn(Optional.of(ACCOUNT));
        mvc.perform(post("/admin/users/7").with(csrf()).param("username", "alice")
                        .param("roles", "ROLE_USER").param("email", "invalid-email"))
                .andExpect(status().isBadRequest()).andExpect(model().attribute("errorKey", "users.error.profile"));
        verify(service, never()).updateUser(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void lastAdministratorCannotBeDemotedThroughTheEditForm() throws Exception {
        when(service.getUser(7L)).thenReturn(Optional.of(ACCOUNT));
        when(service.updateUser(eq(7L), any(), any())).thenThrow(new UserManagementService.ValidationException("Last admin"));
        mvc.perform(post("/admin/users/7").with(csrf()).param("username", "alice").param("roles", "ROLE_USER"))
                .andExpect(status().isBadRequest()).andExpect(model().attribute("errorKey", "users.error.lastAdmin"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void resetRejectsMismatchAndUtf8ByteOverflowWithoutEchoingCredentials() throws Exception {
        when(service.getUser(7L)).thenReturn(Optional.of(ACCOUNT));
        mvc.perform(post("/admin/users/7/password").with(csrf()).param("newPassword", PASSWORD)
                        .param("confirmPassword", "different-password"))
                .andExpect(status().isBadRequest()).andExpect(model().attribute("errorKey", "users.error.passwordMismatch"))
                .andExpect(content().string(not(containsString(PASSWORD))));
        for (String value : List.of("", "short", "ä".repeat(37))) {
            mvc.perform(post("/admin/users/7/password").with(csrf()).param("newPassword", value).param("confirmPassword", value))
                    .andExpect(status().isBadRequest()).andExpect(model().attribute("errorKey", "users.error.passwordLength"));
        }
        verify(service, never()).changePassword(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void serviceValidationOnCreationIsVisibleWithoutSecrets() throws Exception {
        when(service.createUser(any(), any(), any())).thenThrow(new UserManagementService.ValidationException("Invalid account"));
        mvc.perform(post("/admin/users").with(csrf()).param("username", "alice").param("roles", "ROLE_USER")
                        .param("newPassword", PASSWORD).param("confirmPassword", PASSWORD))
                .andExpect(status().isBadRequest()).andExpect(model().attribute("errorKey", "users.error.profile"))
                .andExpect(content().string(not(containsString(PASSWORD))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void serviceValidationOnResetIsVisibleWithoutSecrets() throws Exception {
        when(service.getUser(7L)).thenReturn(Optional.of(ACCOUNT));
        doThrow(new UserManagementService.ValidationException("Invalid password")).when(service).changePassword(eq(7L), any(), any());
        mvc.perform(post("/admin/users/7/password").with(csrf()).param("newPassword", PASSWORD).param("confirmPassword", PASSWORD))
                .andExpect(status().isBadRequest()).andExpect(model().attribute("errorKey", "users.error.passwordLength"))
                .andExpect(content().string(not(containsString(PASSWORD))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void missingAccountAtMutationTimeIsNotReportedAsSuccess() throws Exception {
        when(service.getUser(7L)).thenReturn(Optional.of(ACCOUNT));
        when(service.updateUser(eq(7L), any(), any())).thenThrow(new UserManagementService.NotFoundException("Missing"));
        doThrow(new UserManagementService.NotFoundException("Missing")).when(service).changePassword(eq(7L), any(), any());
        when(service.disableUser(eq(7L), any())).thenThrow(new UserManagementService.NotFoundException("Missing"));
        mvc.perform(post("/admin/users/7").with(csrf()).param("username", "alice").param("roles", "ROLE_USER"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/admin/users/7/password").with(csrf()).param("newPassword", PASSWORD).param("confirmPassword", PASSWORD))
                .andExpect(status().isNotFound());
        mvc.perform(post("/admin/users/7/status").with(csrf()).param("enabled", "false"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "operator", roles = "ADMIN")
    void successRedirectsKeepTheServletContextAndNeverIncludeCredentials() throws Exception {
        when(service.getUser(7L)).thenReturn(Optional.of(ACCOUNT));
        // This MVC slice includes message bundles, not the application locale interceptor.
        // Set the resolved request locale explicitly, as in the German page-rendering test.
        mvc.perform(post("/taxonomy/admin/users").contextPath("/taxonomy").with(csrf())
                        .locale(java.util.Locale.GERMAN).param("username", "alice").param("roles", "ROLE_USER")
                        .param("newPassword", PASSWORD).param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/taxonomy/admin/users?lang=de"));
        mvc.perform(post("/taxonomy/admin/users/7").contextPath("/taxonomy").with(csrf())
                        .locale(java.util.Locale.GERMAN).param("username", "alice").param("roles", "ROLE_USER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/taxonomy/admin/users/7?lang=de"));
        mvc.perform(post("/taxonomy/admin/users/7/password").contextPath("/taxonomy").with(csrf())
                        .locale(java.util.Locale.GERMAN).param("newPassword", PASSWORD).param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/taxonomy/admin/users/7?lang=de"));
        mvc.perform(post("/taxonomy/admin/users/7/status").contextPath("/taxonomy").with(csrf())
                        .locale(java.util.Locale.GERMAN).param("enabled", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/taxonomy/admin/users?lang=de"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableMethodSecurity
    static class Security {
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            // The real filter proxy translates the controller's method authorization.
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults()).build();
        }
    }
}
