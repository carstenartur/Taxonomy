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
    void adaptersAreDeliveredInRequestResponseDecisionCompatibilityAndRecoveryOrder() throws Exception {
        var result = mockMvc.perform(get("/js/portfolio/taxonomy-portfolio-async.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/javascript"))
                .andExpect(content().string(containsString("Portfolio product request normalization")))
                .andExpect(content().string(containsString("Portfolio analysis response normalization")))
                .andExpect(content().string(containsString("Guided portfolio decisions")))
                .andExpect(content().string(containsString("Non-blocking portfolio analysis jobs")))
                .andExpect(content().string(containsString(
                        "Server-backed portfolio analysis job synchronization")))
                .andReturn();
        String script = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(
                script.indexOf("Portfolio product request normalization"))
                .isLessThan(script.indexOf("Portfolio analysis response normalization"));
        org.assertj.core.api.Assertions.assertThat(
                script.indexOf("Portfolio analysis response normalization"))
                .isLessThan(script.indexOf("Guided portfolio decisions"));
        org.assertj.core.api.Assertions.assertThat(
                script.indexOf("Guided portfolio decisions"))
                .isLessThan(script.indexOf("Non-blocking portfolio analysis jobs"));
        org.assertj.core.api.Assertions.assertThat(
                script.indexOf("Non-blocking portfolio analysis jobs"))
                .isLessThan(script.indexOf(
                        "Server-backed portfolio analysis job synchronization"));
        org.assertj.core.api.Assertions.assertThat(script)
                .contains("payload.verifiedAt = parsed.toISOString()")
                .contains("/^\\/api\\/products\\/?$/")
                .contains("registerWithJobCenter(absoluteLocation, job)")
                .contains("status: registered ? 200 : 202")
                .contains("headers.set('Location', location)")
                .contains("window.taxonomyPortfolioRegisterJob")
                .contains("registerJob(resolved.toString(), job)")
                .contains("synchronizeCurrentProjectJobs")
                .contains("/analysis-jobs")
                .contains("input.setAttribute('aria-describedby'")
                .contains("node.parentCode")
                .contains("node.level")
                .doesNotContain("node.hierarchyPath")
                .contains("if (!projectId)")
                .contains("projectRequired");
    }
}
