package com.taxonomy.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves that USER may transform DSL text but cannot materialize it. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.ai.gemini.api-key=",
    "spring.ai.openai.api-key=",
    "embedding.enabled=false"
})
class DslReadOnlyAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "USER")
    void userCanParseValidateAndFormatDslWithoutMutatingState() throws Exception {
        String dsl = """
                element CP-1023 type Capability {
                  title: "Read-only validation";
                }
                """;

        mockMvc.perform(post("/api/dsl/parse")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(post("/api/dsl/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(post("/api/dsl/format")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userStillCannotMaterializeDsl() throws Exception {
        mockMvc.perform(post("/api/dsl/materialize")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("element CP-1023 type Capability {}"))
                .andExpect(status().isForbidden());
    }
}
