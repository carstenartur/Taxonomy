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
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
    private PortfolioAnalysisPersistenceService analyses;

    // The fixture imports retained evidence from before model-bound admission.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.taxonomy.analysis.usecase.AnalysisAdmissionModelPort modelAdmission;

    @Test
    void rendersConsistentProjectFormatsAndRequirementScope() {
        WorkspaceContext context = context();
        var project = projectService.createProject(
                new CreateProjectRequest(
                        "P-" + shortId(), "Report project", "Report scope",
                        ProjectStatus.ACTIVE, null, null, null, null),
                context.username(), context);
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
                .contains(project.projectKey(), "REQ-001", "Reproducibility baseline", snapshotId, "frozen-catalogue", "18.5% assessed", "50%");
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
        String user = "report-" + shortId();
        return new WorkspaceContext(user, "ws-" + user, "draft",
                systemRepositoryService.getPrimaryRepository().getRepositoryId());
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
