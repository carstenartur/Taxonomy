package com.taxonomy.security.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountContextControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "reader", authorities = {"ROLE_USER", "FACTOR_PASSWORD"})
    void userContextIsReadOnlyAndContainsOnlyApplicationRoles() throws Exception {
        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("reader"))
                .andExpect(jsonPath("$.roles", hasSize(1)))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.architectureMutationAllowed").value(false))
                .andExpect(jsonPath("$.administrator").value(false));
    }

    @Test
    @WithMockUser(username = "architect", roles = {"USER", "ARCHITECT"})
    void architectContextAllowsArchitectureMutation() throws Exception {
        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[?(@ == 'ARCHITECT')]").exists())
                .andExpect(jsonPath("$.architectureMutationAllowed").value(true))
                .andExpect(jsonPath("$.administrator").value(false));
    }

    @Test
    @WithMockUser(username = "administrator", roles = {"USER", "ARCHITECT", "ADMIN"})
    void adminContextExposesAdministratorCapability() throws Exception {
        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.architectureMutationAllowed").value(true))
                .andExpect(jsonPath("$.administrator").value(true));
    }
}
