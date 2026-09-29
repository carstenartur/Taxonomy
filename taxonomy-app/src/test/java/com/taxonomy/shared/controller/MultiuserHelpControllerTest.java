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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Covers the user-visible route, localized contents and exported translation key. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.ai.gemini.api-key=", "spring.ai.openai.api-key=",
    "spring.ai.deepseek.api-key=", "spring.ai.qwen.api-key=",
    "spring.ai.llama.api-key=", "spring.ai.mistral.api-key="
})
@WithMockUser
class MultiuserHelpControllerTest {
    @Autowired private MockMvc mvc;

    @Test
    void multiuserAnalysisGuideIsReachableAndTranslatedInBothLanguages() throws Exception {
        for (String language : List.of("en", "de")) {
            String title = language.equals("de") ? "Mehrbenutzer-Analyse" : "Multi-user analysis";
            String heading = language.equals("de")
                    ? "Begrenzte Mehrbenutzer-Analyse" : "Bounded multi-user analysis";
            mvc.perform(get("/help").param("lang", language).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.filename == 'MULTIUSER_ANALYSIS')].title").value(title));
            mvc.perform(get("/help/MULTIUSER_ANALYSIS").param("lang", language))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(containsString(heading)))
                    .andExpect(content().string(containsString("taxonomy.analysis.max-concurrent-jobs")))
                    .andExpect(content().string(containsString("taxonomy.llm.max-concurrent-requests")));
            mvc.perform(get("/api/i18n/" + language))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$['help.toc.MULTIUSER_ANALYSIS']").value(title));
        }
    }
}
