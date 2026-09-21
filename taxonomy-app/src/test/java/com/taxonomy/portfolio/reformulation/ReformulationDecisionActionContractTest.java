package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "architect", roles = "ARCHITECT")
class ReformulationDecisionActionContractTest extends ReformulationWorkflowFixture {
    @Test
    void notApplicableCannotBeSilentlyConvertedToOpenDeferral() throws Exception {
        var proposal = seed();
        mvc.perform(post(base() + "/" + proposal.id() + "/answers").with(csrf())
                        .header("If-Match", "\"2\"").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("questionId", "channel",
                                "action", "NOT_APPLICABLE", "values", List.of("Still open"),
                                "rationale", "An explicit not-applicable decision"))))
                .andExpect(status().is(422));
        var unchanged = reformulations.get(project.id(), requirement.id(), proposal.id(), "architect", context);
        assertThat(unchanged.currentRevision()).isEqualTo(proposal.currentRevision());

        var accepted = mvc.perform(post(base() + "/" + proposal.id() + "/answers").with(csrf())
                        .header("If-Match", "\"2\"").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("questionId", "channel",
                                "action", "NOT_APPLICABLE", "values", List.of(),
                                "rationale", "An explicit not-applicable decision"))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var result = json.readTree(accepted.getContentAsString());
        assertThat(result.at("/currentRevision/answers/0/disposition").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(result.at("/currentRevision/answers/0/state").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(result.at("/currentRevision/questions/0/state").asText()).isEqualTo("NOT_APPLICABLE");
    }
}
