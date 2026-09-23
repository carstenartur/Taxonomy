package com.taxonomy.shared.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.ai.gemini.api-key=", "spring.ai.openai.api-key=",
    "spring.ai.deepseek.api-key=", "spring.ai.qwen.api-key=",
    "spring.ai.llama.api-key=", "spring.ai.mistral.api-key="
})
@WithMockUser
class SparxHelpControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void sparxCurrentViewGuideIsReachableInBothLanguages() throws Exception {
        for (String language : List.of("en", "de")) {
            mvc.perform(get("/help/SPARX_CURRENT_VIEW_EXPORT").param("lang", language))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("architecture.xmi")))
                    .andExpect(content().string(containsString("manifest.json")));
            mvc.perform(get("/help").param("lang", language).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].filename", hasItem("SPARX_CURRENT_VIEW_EXPORT")));
            mvc.perform(get("/api/i18n/" + language))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$['help.toc.SPARX_CURRENT_VIEW_EXPORT']").value(
                            language.equals("de") ? "Angezeigte Architektur für Sparx EA exportieren"
                                    : "Export the current architecture to Sparx EA"));
        }
    }
}
