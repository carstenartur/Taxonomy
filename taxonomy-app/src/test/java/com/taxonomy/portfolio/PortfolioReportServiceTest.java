package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioReportService;
import com.taxonomy.portfolio.service.PortfolioReportService.Format;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PortfolioReportServiceTest {

    @Autowired
    private PortfolioReportService reportService;

    @Autowired
    private ProjectPortfolioService projectService;

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
                .contains(project.projectKey(), "REQ-001", "Reproducibility baseline");
        assertThat(new String(html.bytes(), StandardCharsets.UTF_8))
                .contains("<!doctype html>", project.projectKey(), "REQ-001")
                .doesNotContain("REQ-999");
        assertThat(new String(json.bytes(), StandardCharsets.UTF_8))
                .contains("\"reportType\" : \"REQUIREMENT\"", "\"requirementKey\" : \"REQ-001\"");
        assertThat(new String(csv.bytes(), StandardCharsets.UTF_8)).startsWith("row");
        assertThat(docx.bytes()).startsWith(new byte[]{'P', 'K'});
        assertThat(docx.filename()).endsWith(".docx");
    }

    private WorkspaceContext context() {
        String user = "report-" + shortId();
        return new WorkspaceContext(user, "ws-" + user, "draft");
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
