package com.taxonomy.portfolio.workbench;

import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.workbench.ArchitectureSnapshotExportService.SnapshotArtifact;
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
import java.util.regex.Pattern;

/** Page and API adapter for the read-only architecture workbench. */
@Controller
@Tag(name = "Architecture Workbench")
public class ArchitectureWorkbenchController {

    static final String SNAPSHOT_HEADER = "X-Taxonomy-Architecture-Snapshot";
    static final String COMMIT_HEADER = "X-Taxonomy-Architecture-Commit";
    static final String GRAPH_SHA_HEADER = "X-Taxonomy-Architecture-Graph-SHA256";
    static final String PROFILE_HEADER = "X-Taxonomy-Export-Profile";
    static final String CONTENT_SHA_HEADER = "X-Taxonomy-Export-Content-SHA256";

    private static final Pattern SAFE_HEADER_VALUE = Pattern.compile("[\\x21-\\x7E]{1,256}");

    private final ArchitectureWorkbenchService service;
    private final ArchitectureSnapshotExportService exportService;
    private final ProjectPortfolioService projectService;
    private final WorkspaceResolver workspaceResolver;

    public ArchitectureWorkbenchController(
            ArchitectureWorkbenchService service,
            ArchitectureSnapshotExportService exportService,
            ProjectPortfolioService projectService,
            WorkspaceResolver workspaceResolver) {
        this.service = service;
        this.exportService = exportService;
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
    public ResponseEntity<byte[]> svg(
            @PathVariable Long projectId,
            @PathVariable String snapshotId) {
        return export(projectId, snapshotId, "svg");
    }

    @GetMapping(value = "/api/projects/{projectId}/architecture-workbench/{snapshotId}.pdf",
            produces = MediaType.APPLICATION_PDF_VALUE)
    @ResponseBody
    @Operation(summary = "Export the architecture snapshot as a vector PDF")
    public ResponseEntity<byte[]> pdf(
            @PathVariable Long projectId,
            @PathVariable String snapshotId) {
        return export(projectId, snapshotId, "pdf");
    }

    @GetMapping("/api/projects/{projectId}/architecture-workbench/{snapshotId}/exports/{formatId}")
    @ResponseBody
    @Operation(summary = "Download one format derived from the exact immutable architecture snapshot")
    public ResponseEntity<byte[]> export(
            @PathVariable Long projectId,
            @PathVariable String snapshotId,
            @PathVariable String formatId) {
        RequestScope scope = scope();
        SnapshotArtifact artifact = exportService.export(
                projectId, snapshotId, scope.username(), scope.context(), formatId);
        return response(artifact);
    }

    private static ResponseEntity<byte[]> response(SnapshotArtifact artifact) {
        byte[] content = artifact.content();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(artifact.format().mediaType()));
        headers.setContentLength(content.length);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(artifact.format().fileName(), StandardCharsets.UTF_8)
                .build());
        headers.setCacheControl("private, no-store");
        headers.setETag("\"sha256-" + artifact.contentSha256() + "\"");
        headers.set(SNAPSHOT_HEADER, requiredHeader("snapshotId", artifact.snapshotId()));
        setOptionalHeader(headers, COMMIT_HEADER, artifact.commitSha());
        headers.set(GRAPH_SHA_HEADER,
                requiredHeader("graphSha256", artifact.graphSha256()));
        headers.set(PROFILE_HEADER,
                requiredHeader("exportProfile", artifact.format().profileId()));
        headers.set(CONTENT_SHA_HEADER,
                requiredHeader("contentSha256", artifact.contentSha256()));
        return ResponseEntity.ok().headers(headers).body(content);
    }

    private static String requiredHeader(String field, String value) {
        String normalized = value == null ? "" : value.strip();
        if (!SAFE_HEADER_VALUE.matcher(normalized).matches()) {
            throw new IllegalStateException(
                    "Architecture export " + field + " is not safe for an HTTP header");
        }
        return normalized;
    }

    private static void setOptionalHeader(
            HttpHeaders headers, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        headers.set(name, requiredHeader(name, value));
    }

    private RequestScope scope() {
        return new RequestScope(
                workspaceResolver.resolveCurrentUsername(),
                workspaceResolver.resolveCurrentContext());
    }

    private record RequestScope(String username, WorkspaceContext context) {
    }
}
