package com.taxonomy.portfolio.workbench;

import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;

/** Page and API adapter for the read-only architecture workbench. */
@Controller
@Tag(name = "Architecture Workbench")
public class ArchitectureWorkbenchController {

    private static final MediaType SVG = MediaType.parseMediaType("image/svg+xml");

    private final ArchitectureWorkbenchService service;
    private final ProjectPortfolioService projectService;
    private final WorkspaceResolver workspaceResolver;

    public ArchitectureWorkbenchController(
            ArchitectureWorkbenchService service,
            ProjectPortfolioService projectService,
            WorkspaceResolver workspaceResolver) {
        this.service = service;
        this.projectService = projectService;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping("/architecture/workbench")
    public String workbenchPage(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String snapshotId,
            Model model) {
        model.addAttribute("projectId", projectId);
        model.addAttribute("snapshotId", snapshotId);
        return "architecture-workbench";
    }

    @GetMapping("/projects/{projectId}/requirements/{requirementId}/architecture")
    public String currentRequirementArchitecture(
            @PathVariable Long projectId,
            @PathVariable Long requirementId) {
        RequestScope scope = scope();
        RequirementView requirement = projectService.getRequirement(
                projectId, requirementId, scope.username(), scope.context());
        String snapshotId = requirement.currentAnalysisSnapshotId();
        if (snapshotId == null || snapshotId.isBlank()) {
            throw PortfolioException.conflict(
                    "Requirement " + requirement.requirementKey()
                            + " has no current architecture snapshot. Analyze the current version first.");
        }
        String target = UriComponentsBuilder.fromPath("/architecture/workbench")
                .queryParam("projectId", projectId)
                .queryParam("snapshotId", snapshotId)
                .build()
                .encode()
                .toUriString();
        return "redirect:" + target;
    }

    @GetMapping("/api/projects/{projectId}/architecture-workbench/{snapshotId}")
    @ResponseBody
    @Operation(summary = "Load one immutable architecture snapshot as a render-ready scene")
    public Projection projection(
            @PathVariable Long projectId,
            @PathVariable String snapshotId) {
        RequestScope scope = scope();
        return service.load(projectId, snapshotId, scope.username(), scope.context());
    }

    @GetMapping(value = "/api/projects/{projectId}/architecture-workbench/{snapshotId}.svg",
            produces = "image/svg+xml")
    @ResponseBody
    @Operation(summary = "Export the architecture snapshot as deterministic standalone SVG")
    public ResponseEntity<String> svg(
            @PathVariable Long projectId,
            @PathVariable String snapshotId) {
        RequestScope scope = scope();
        String content = service.renderSvg(projectId, snapshotId, scope.username(), scope.context());
        return ResponseEntity.ok()
                .contentType(SVG)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition("architecture", "svg"))
                .body(content);
    }

    @GetMapping(value = "/api/projects/{projectId}/architecture-workbench/{snapshotId}.pdf",
            produces = MediaType.APPLICATION_PDF_VALUE)
    @ResponseBody
    @Operation(summary = "Export the architecture snapshot as a vector PDF")
    public ResponseEntity<byte[]> pdf(
            @PathVariable Long projectId,
            @PathVariable String snapshotId) {
        RequestScope scope = scope();
        byte[] content = service.renderPdf(projectId, snapshotId, scope.username(), scope.context());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition("architecture", "pdf"))
                .body(content);
    }

    private RequestScope scope() {
        return new RequestScope(
                workspaceResolver.resolveCurrentUsername(),
                workspaceResolver.resolveCurrentContext());
    }

    private static String disposition(String name, String extension) {
        return ContentDisposition.attachment()
                .filename(name + "." + extension, StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    private record RequestScope(String username, WorkspaceContext context) {
    }
}
