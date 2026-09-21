package com.taxonomy.portfolio.reformulation;

import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "architect", roles = "ARCHITECT")
class ReformulationCancellationApiTest extends ReformulationWorkflowFixture {
    @Test void cancellationRequiresCsrfAndCurrentRevisionAndDoesNotEditTheProposal() throws Exception {
        var proposal = seed();
        var before = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        var run = reformulations.beginRun(project.id(), requirement.id(), proposal.id(), 2,
                "TEST", "test", "p", "s", "frozen", "architect", context);
        String url = base() + "/" + proposal.id() + "/synthesis-runs/" + run.id() + "/cancel";
        mvc.perform(post(url).header("If-Match", "\"2\"")).andExpect(status().isForbidden());
        mvc.perform(post(url).with(csrf())).andExpect(status().isPreconditionRequired());
        mvc.perform(post(url).with(csrf()).header("If-Match", "2")).andExpect(status().isBadRequest());
        mvc.perform(post(url).with(csrf()).header("If-Match", "\"1\"")).andExpect(status().isPreconditionFailed());
        assertThat(reformulations.runs(project.id(), requirement.id(), proposal.id(), "architect", context).getLast().status()).isEqualTo("QUEUED");
        mvc.perform(post(url).with(csrf()).header("If-Match", "\"2\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        var cancelledAt = jdbc.queryForObject("select cancelled_at from reformulation_run where id=?", java.time.OffsetDateTime.class, run.id());
        mvc.perform(post(url).with(csrf()).header("If-Match", "\"2\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("select cancelled_at from reformulation_run where id=?", java.time.OffsetDateTime.class, run.id())).isEqualTo(cancelledAt);
        assertThat(jdbc.queryForObject("select cancelled_by from reformulation_run where id=?", String.class, run.id())).isEqualTo("architect");
        assertThat(reformulations.get(project.id(), requirement.id(), proposal.id(), "architect", context).currentRevision()).isEqualTo(proposal.currentRevision());
        assertThat(projects.getRequirement(project.id(), requirement.id(), "architect", context)).isEqualTo(before);
    }

    @Test void foreignWorkspaceCannotCancelAnotherOffersRun() throws Exception {
        var proposal = seed();
        var run = reformulations.beginRun(project.id(), requirement.id(), proposal.id(), 2,
                "TEST", "test", "p", "s", "frozen", "architect", context);
        select(new WorkspaceContext("architect", "foreign-workspace", context.currentBranch(), context.repositoryId()));
        mvc.perform(post(base() + "/" + proposal.id() + "/synthesis-runs/" + run.id() + "/cancel")
                .with(csrf()).header("If-Match", "\"2\""))
                .andExpect(status().isNotFound());
        assertThat(reformulations.runs(project.id(), requirement.id(), proposal.id(), "architect", context).getLast().status()).isEqualTo("QUEUED");
    }
}
