package com.taxonomy.portfolio;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PortfolioScriptBundleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void guidedHandlersAreDeliveredBeforeCompatibilityHandlers() throws Exception {
        var result = mockMvc.perform(get("/js/portfolio/taxonomy-portfolio-async.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/javascript"))
                .andExpect(content().string(containsString("Guided portfolio decisions")))
                .andExpect(content().string(containsString("Non-blocking portfolio analysis jobs")))
                .andReturn();
        String script = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(
                script.indexOf("Guided portfolio decisions"))
                .isLessThan(script.indexOf("Non-blocking portfolio analysis jobs"));
    }
}