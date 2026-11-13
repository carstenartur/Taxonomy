package com.taxonomy.portfolio;

import org.junit.jupiter.api.Tag;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ProductCoverageGap;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.PortfolioReportService;
import com.taxonomy.portfolio.service.PortfolioReportService.Format;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.controller.PortfolioExceptionHandler;
import com.taxonomy.portfolio.controller.ProjectProductGapReviewController;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@SpringBootTest
@Tag("ci-app-shard-domain-integration")
class PortfolioReportServiceTest {

    @Autowired
    private SystemRepositoryService systemRepositoryService;

    @Autowired
    private PortfolioReportService reportService;

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private com.taxonomy.portfolio.service.ProjectProductGapReviewService gapReviews;

    @Autowired
    private PortfolioAnalysisPersistenceService analyses;

    // The fixture imports retained evidence from before model-bound admission.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.taxonomy.analysis.usecase.AnalysisAdmissionModelPort modelAdmission;

    @Test
    @WithMockUser(username = "project-gap-reviewer", roles = "USER")
    void rendersConsistentProjectFormatsAndRequirementScope() throws Exception {
        WorkspaceContext context = context();
        var project = projectService.createProject(
                new CreateProjectRequest(
                        "P-" + shortId(), "Report project", "Report scope",
                        ProjectStatus.ACTIVE, null, null, null, null),
                context.username(), context);
        var emptyProject = reportService.render(
                project.id(), null, Format.HTML, "taxonomy", context.username(), context);
        assertThat(new String(emptyProject.bytes(), StandardCharsets.UTF_8)).contains(project.projectKey());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reportService.render(
                project.id(), 123456789L, Format.HTML, "taxonomy", context.username(), context))
                .isInstanceOf(com.taxonomy.portfolio.service.PortfolioException.class)
                .hasMessageContaining("Requirement not found");
        var requirement = projectService.createRequirement(
                project.id(),
                new CreateRequirementRequest(
                        "REQ-001", "Traceable report", "The system shall create traceable reports.",
                        RequirementStatus.DRAFT, 80, Criticality.HIGH,
                        RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED,
                        context.username(), "Initial report requirement", null),
                context.username(), context);

        var snapshotId = UUID.randomUUID().toString();
        var job = analyses.createOrReuseJob(project.id(), List.of(requirement.id()), null, 25,
                shortId(), context.username(), context);
        var evidence = new AnalysisResult(Map.of("IP-F", 80, "IP-P", 0), List.of());
        evidence.setStatus("PARTIAL");
        evidence.setProductCoverageGaps(List.of(new ProductCoverageGap("IP-F", "Family", 80,
                List.of("IP-P"), "Catalogue lacks this capability", 50, Map.of("IP-P", 18.5))));
        analyses.persistSnapshot(job.items().getFirst().id(), job.id(), project.id(),
                PortfolioScope.key(context.username(), context), snapshotId, "session-" + snapshotId,
                evidence, null, null, null, null, null, "frozen-prompt", "frozen-catalogue",
                context.username(), context, 1);

        var workspace = mock(WorkspaceResolver.class);
        when(workspace.resolveCurrentUsername()).thenCallRealMethod();
        when(workspace.resolveCurrentContext()).thenReturn(context);
        when(workspace.resolveUnambiguousContextForRead()).thenReturn(context);
        var routes = standaloneSetup(new ProjectProductGapReviewController(gapReviews, workspace))
                .setControllerAdvice(new PortfolioExceptionHandler()).build();
        var gaps = "/api/projects/{projectId}/product-gaps";
        routes.perform(get(gaps, project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].classification").value("UNDECIDED"))
                .andExpect(jsonPath("$[0].observations[0].snapshotId").value(snapshotId));

        var gap = gapReviews.list(project.id(), context.username(), context).getFirst();
        assertThat(gap.classification().name()).isEqualTo("UNDECIDED");
        var classify = new com.taxonomy.portfolio.service.ProjectProductGapReviewService.ReviewRequest(0,
                gap.evidenceFingerprint(), com.taxonomy.portfolio.model.ProductGapReview.Classification.PROCURE, "Reviewed candidates");
        var request = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(classify);
        var reviews = gaps + "/{gapKey}/reviews";
        routes.perform(post(reviews, project.id(), gap.reviewKey()).contentType("application/json").content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.history[0].reviewer").value(context.username()));
        routes.perform(post(reviews, project.id(), gap.reviewKey()).contentType("application/json").content(request))
                .andExpect(status().isConflict());
        var foreign = new WorkspaceContext(context.username(), "other-" + context.workspaceId(),
                context.currentBranch(), context.repositoryId());
        when(workspace.resolveCurrentContext()).thenReturn(foreign);
        when(workspace.resolveUnambiguousContextForRead()).thenReturn(foreign);
        routes.perform(get(gaps, project.id())).andExpect(status().isNotFound());
        routes.perform(post(reviews, project.id(), gap.reviewKey()).contentType("application/json").content(request))
                .andExpect(status().isNotFound());
        var reviewed = gapReviews.list(project.id(), context.username(), context).getFirst();
        assertThat(reviewed.revision()).isOne();
        assertThat(reviewed.history().getFirst().reviewer()).isEqualTo(context.username());
        assertThat(reviewed.history().getFirst().evidenceFingerprint()).isEqualTo(gap.evidenceFingerprint());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gapReviews.review(project.id(), gap.reviewKey(),
                classify, context.username(), context)).isInstanceOf(com.taxonomy.portfolio.service.PortfolioException.class)
                .hasMessageContaining("changed");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gapReviews.review(project.id(), gap.reviewKey(),
                new com.taxonomy.portfolio.service.ProjectProductGapReviewService.ReviewRequest(1, "stale",
                        com.taxonomy.portfolio.model.ProductGapReview.Classification.CHANGE, "New information"),
                context.username(), context)).hasMessageContaining("changed");
        assertThat(gapReviews.list(project.id(), context.username(), context).getFirst().history()).hasSize(1);

        var markdown = reportService.render(
                project.id(), null, Format.MARKDOWN, "taxonomy", context.username(), context);
        var html = reportService.render(
                project.id(), requirement.id(), Format.HTML, "taxonomy", context.username(), context);
        var json = reportService.render(
                project.id(), requirement.id(), Format.JSON, "taxonomy", context.username(), context);
        var csv = reportService.render(
                project.id(), null, Format.CSV, "taxonomy", context.username(), context);
        var docx = reportService.render(
                project.id(), requirement.id(), Format.DOCX, "taxonomy", context.username(), context);

        assertThat(new String(markdown.bytes(), StandardCharsets.UTF_8))
                .contains(project.projectKey(), "REQ-001", "Reproducibility baseline", snapshotId, "frozen-catalogue", "18.5% assessed", "50%", "PROCURE (v1)");
        assertThat(new String(html.bytes(), StandardCharsets.UTF_8))
                .contains("<!doctype html>", project.projectKey(), "REQ-001")
                .contains(snapshotId, "18.5% assessed", "50%").doesNotContain("REQ-999");
        assertThat(new String(json.bytes(), StandardCharsets.UTF_8))
                .contains("\"reportType\" : \"REQUIREMENT\"", "\"requirementKey\" : \"REQ-001\"");
        assertThat(new String(csv.bytes(), StandardCharsets.UTF_8)).startsWith("row");
        assertThat(docx.bytes()).startsWith(new byte[]{'P', 'K'});
        assertThat(docx.filename()).endsWith(".docx");
    }

    private WorkspaceContext context() {
        String user = SecurityContextHolder.getContext().getAuthentication().getName();
        return new WorkspaceContext(user, "ws-" + shortId(), "draft",
                systemRepositoryService.getPrimaryRepository().getRepositoryId());
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
