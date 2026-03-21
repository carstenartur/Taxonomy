package com.taxonomy.security.controller;

import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link ChangePasswordController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "testuser", roles = "USER")
class ChangePasswordControllerTest {

    private static final String CURRENT_PASSWORD = "OldPassword1";
    private static final String NEW_PASSWORD = "NewPassword1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        AppUser user = userRepository.findByUsername("testuser")
                .orElseGet(() -> {
                    AppUser u = new AppUser();
                    u.setUsername("testuser");
                    u.setEnabled(true);
                    return u;
                });
        user.setPasswordHash(passwordEncoder.encode(CURRENT_PASSWORD));
        userRepository.save(user);
    }

    @Test
    void getChangePasswordReturnsFormView() throws Exception {
        mockMvc.perform(get("/change-password"))
                .andExpect(status().isOk())
                .andExpect(view().name("change-password"));
    }

    @Test
    void changePasswordWithValidInputSucceeds() throws Exception {
        mockMvc.perform(post("/change-password")
                        .param("currentPassword", CURRENT_PASSWORD)
                        .param("newPassword", NEW_PASSWORD)
                        .param("confirmPassword", NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(view().name("change-password"))
                .andExpect(model().attributeExists("success"))
                .andExpect(model().attribute("success", "Password changed successfully."));
    }

    @Test
    void changePasswordWithIncorrectCurrentPasswordReturnsError() throws Exception {
        mockMvc.perform(post("/change-password")
                        .param("currentPassword", "WrongPassword")
                        .param("newPassword", NEW_PASSWORD)
                        .param("confirmPassword", NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(view().name("change-password"))
                .andExpect(model().attribute("error", "Current password is incorrect."));
    }

    @Test
    void changePasswordWithShortNewPasswordReturnsError() throws Exception {
        mockMvc.perform(post("/change-password")
                        .param("currentPassword", CURRENT_PASSWORD)
                        .param("newPassword", "short")
                        .param("confirmPassword", "short"))
                .andExpect(status().isOk())
                .andExpect(view().name("change-password"))
                .andExpect(model().attribute("error", "New password must be at least 8 characters."));
    }

    @Test
    void changePasswordWithNonMatchingConfirmReturnsError() throws Exception {
        mockMvc.perform(post("/change-password")
                        .param("currentPassword", CURRENT_PASSWORD)
                        .param("newPassword", NEW_PASSWORD)
                        .param("confirmPassword", "DifferentPassword"))
                .andExpect(status().isOk())
                .andExpect(view().name("change-password"))
                .andExpect(model().attribute("error", "New passwords do not match."));
    }

    @Test
    void changePasswordWithSameAsCurrentReturnsError() throws Exception {
        mockMvc.perform(post("/change-password")
                        .param("currentPassword", CURRENT_PASSWORD)
                        .param("newPassword", CURRENT_PASSWORD)
                        .param("confirmPassword", CURRENT_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(view().name("change-password"))
                .andExpect(model().attribute("error",
                        "New password must be different from the current password."));
    }

    @Test
    @WithMockUser(username = "nonexistentuser", roles = "USER")
    void changePasswordWhenUserNotFoundReturnsError() throws Exception {
        mockMvc.perform(post("/change-password")
                        .param("currentPassword", CURRENT_PASSWORD)
                        .param("newPassword", NEW_PASSWORD)
                        .param("confirmPassword", NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(view().name("change-password"))
                .andExpect(model().attribute("error", "User not found."));
    }
}
