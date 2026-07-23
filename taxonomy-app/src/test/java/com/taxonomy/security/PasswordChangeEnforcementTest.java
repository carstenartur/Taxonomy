package com.taxonomy.security;

import com.taxonomy.security.model.AppRole;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "taxonomy.security.require-password-change=true")
@AutoConfigureMockMvc
class PasswordChangeEnforcementTest {

    private static final String USERNAME = "password-restricted-user";
    private static final String TEMPORARY_PASSWORD = "TemporaryPassword1";
    private static final String REPLACEMENT_PASSWORD = "ReplacementPassword1";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        AppRole userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new AppRole("ROLE_USER")));
        AppUser user = userRepository.findByUsername(USERNAME).orElseGet(AppUser::new);
        user.setUsername(USERNAME);
        user.setPasswordHash(passwordEncoder.encode(TEMPORARY_PASSWORD));
        user.setEnabled(true);
        user.setMustChangePassword(true);
        user.setRoles(Set.of(userRole));
        userRepository.save(user);
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "USER")
    void browserUserIsRedirectedUntilPasswordChanges() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/change-password"));

        mockMvc.perform(get("/change-password"))
                .andExpect(status().isOk());
    }

    @Test
    void basicClientReceivesPreconditionAndCanReplacePasswordThroughApi() throws Exception {
        mockMvc.perform(get("/api/taxonomy")
                        .with(httpBasic(USERNAME, TEMPORARY_PASSWORD)))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.error").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.changePasswordEndpoint")
                        .value("/api/account/change-password"));

        mockMvc.perform(post("/api/account/change-password")
                        .with(httpBasic(USERNAME, TEMPORARY_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "TemporaryPassword1",
                                  "newPassword": "ReplacementPassword1",
                                  "confirmPassword": "ReplacementPassword1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSWORD_CHANGED"));

        AppUser updated = userRepository.findByUsername(USERNAME).orElseThrow();
        assertThat(updated.isMustChangePassword()).isFalse();
        assertThat(passwordEncoder.matches(REPLACEMENT_PASSWORD, updated.getPasswordHash())).isTrue();

        mockMvc.perform(get("/api/taxonomy")
                        .with(httpBasic(USERNAME, REPLACEMENT_PASSWORD)))
                .andExpect(status().isOk());
    }
}
