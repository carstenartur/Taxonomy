package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReformulationBasePathContractTest extends ReformulationIsolationTest {
    @Test
    void createdOfferLocationRetainsTheServletContextPath() throws Exception {
        var response = mvc.perform(post("/taxonomy" + base()).contextPath("/taxonomy")
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(request(snapshot, requirement.currentVersionId())))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        String id = json.readTree(response.getContentAsString()).path("id").asText();
        URI location = URI.create(response.getHeader("Location"));
        assertThat(location.getPath()).isEqualTo("/taxonomy" + base() + "/" + id);
        mvc.perform(get(location.getPath()).contextPath("/taxonomy"))
                .andExpect(status().isOk());
    }

    @Test
    void requirementPageLoadsTheBasePathBootstrapBeforeItsApiClients() throws Exception {
        String page = mvc.perform(get("/taxonomy/projects/" + project.id() + "/requirements/" + requirement.id())
                        .contextPath("/taxonomy"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int bootstrap = page.indexOf("/taxonomy/js/taxonomy-i18n.js");
        int api = page.indexOf("/taxonomy/js/api/portfolio-api.js");
        assertThat(bootstrap).isGreaterThanOrEqualTo(0).isLessThan(api);
    }
}
