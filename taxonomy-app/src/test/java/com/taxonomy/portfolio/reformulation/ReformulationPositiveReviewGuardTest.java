package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortablePortfolioGitService;
import com.taxonomy.reformulation.DecisionQuestion;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "llm.mock=true")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@WithMockUser(username = "architect", roles = "ARCHITECT")
class ReformulationPositiveReviewGuardTest extends ReformulationWorkflowFixture {
    @Autowired ReformulationAdoptionService adoption;
    @Autowired PortablePortfolioGitService git;

    private void adopt(boolean conflict) throws Exception {
        if (conflict) questionTransform = questions -> questions.stream().map(q -> q.id().equals("channel")
                ? new DecisionQuestion(q.id(), q.key(), q.wording(), q.discoveries(), q.affectedStatementIds(),
                        q.answerSchema(), q.prerequisites(), q.dependentQuestionIds(), q.consequences(), DecisionQuestion.State.CONFLICT)
                : q).toList();
        var offer = seed();
        var preview = adoption.preview(project.id(), requirement.id(), offer.id(), offer.currentRevision().number(), "architect", context);
        adoption.adopt(project.id(), requirement.id(), offer.id(), offer.currentRevision().number(),
                new ReformulationAdoptionDtos.ConfirmRequest(UUID.randomUUID().toString(), preview.content().id(),
                        preview.hash(), true, true, "Explicit adoption"), "architect", context);
    }

    private String requirementUrl(long projectId, long requirementId) {
        return "/api/projects/" + projectId + "/requirements/" + requirementId;
    }

    private void positive(long projectId, long requirementId, int expectedStatus) throws Exception {
        mvc.perform(patch(requirementUrl(projectId, requirementId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Reviewed title\",\"status\":\"APPROVED\",\"reviewStatus\":\"CONFIRMED\"}"))
                .andExpect(expectedStatus == 409 ? status().isConflict() : status().isOk());
    }

    @Test void currentLocalConflictCannotBecomePositiveAndFailedPatchIsAtomic() throws Exception {
        adopt(true);
        var before = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        positive(project.id(), requirement.id(), 409);
        var after = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        assertThat(after).isEqualTo(before);
    }

    @Test void openQuestionsAreNonblocking() throws Exception {
        adopt(false);
        positive(project.id(), requirement.id(), 200);
        assertThat(projects.getRequirement(project.id(), requirement.id(), "architect", context).status()).isEqualTo(RequirementStatus.APPROVED);
        assertThat(projects.getRequirement(project.id(), requirement.id(), "architect", context).reviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
    }

    @Test void newerCurrentVersionIsIndependentOfOldConflict() throws Exception {
        adopt(true);
        projects.addRequirementVersion(project.id(), requirement.id(), new CreateRequirementVersionRequest("A new independent source", "Next", null), "architect", context);
        positive(project.id(), requirement.id(), 200);
    }

    @Test void localConflictDoesNotBlockAnotherRequirement() throws Exception {
        adopt(true);
        var other = createRequirement("INDEPENDENT");
        positive(project.id(), other.id(), 200);
        assertThat(projects.getRequirement(project.id(), other.id(), "architect", context).reviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
    }

    @Test void importedCurrentConflictBlocksOnlyMatchingTargetScope() throws Exception {
        adopt(true);
        String dsl = git.exportPortfolio("architect", context);
        var workspace = workspaces.createWorkspace("architect", "Imported guard " + UUID.randomUUID(), "Review guard");
        workspace = workspaces.provisionWorkspaceRepository("architect", workspace.getWorkspaceId());
        WorkspaceContext target = new WorkspaceContext("architect", workspace.getWorkspaceId(), workspace.getCurrentBranch(), workspace.getSourceRepositoryId());
        git.materialize(dsl, "architect", target);
        var importedProject = projects.listProjects("architect", target).getFirst();
        var importedRequirement = projects.listRequirements(importedProject.id(), "architect", target).getFirst();
        select(target);
        var before = projects.getRequirement(importedProject.id(), importedRequirement.id(), "architect", target);
        positive(importedProject.id(), importedRequirement.id(), 409);
        assertThat(projects.getRequirement(importedProject.id(), importedRequirement.id(), "architect", target)).isEqualTo(before);
    }
}
