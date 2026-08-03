package com.taxonomy.portfolio;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "gemini.api.key=", "openai.api.key=", "deepseek.api.key=",
        "qwen.api.key=", "llama.api.key=", "mistral.api.key="
})
@WithMockUser(username = "architect", roles = "ARCHITECT")
class PortfolioPageRoutesTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void requirementDetailHasStableShareableRoute() throws Exception {
        mockMvc.perform(get("/projects/12/requirements/34"))
                .andExpect(status().isOk())
                .andExpect(view().name("requirement-detail"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"requirementMain\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/js/portfolio/requirement-detail.js")));
    }

    @Test
    void projectMatricesHaveStableShareableRoute() throws Exception {
        mockMvc.perform(get("/projects/12/matrices"))
                .andExpect(status().isOk())
                .andExpect(view().name("portfolio-matrices"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"matrixMain\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/js/portfolio/portfolio-matrices.js")));
    }
}
