package com.taxonomy.analysis.session;

import com.taxonomy.analysis.session.AnalysisDraftDtos.AnalysisDraftView;
import com.taxonomy.analysis.session.AnalysisDraftDtos.SaveAnalysisDraftRequest;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST boundary for resumable, user-owned ad-hoc analysis drafts. */
@RestController
@RequestMapping("/api/analysis-drafts")
@Tag(name = "Analysis Working Drafts")
public class AnalysisWorkingDraftController {

    private final AnalysisWorkingDraftService service;
    private final WorkspaceResolver workspaceResolver;

    public AnalysisWorkingDraftController(
            AnalysisWorkingDraftService service,
            WorkspaceResolver workspaceResolver) {
        this.service = service;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping("/{workspaceId}")
    @Operation(summary = "Restore the current user's ad-hoc analysis draft")
    public ResponseEntity<AnalysisDraftView> read(
            @PathVariable String workspaceId,
            HttpServletRequest request) {
        requireMatchingExplicitPin(request, workspaceId);
        return service.read(workspaceResolver.resolveCurrentUsername(), workspaceId)
                .map(this::versionedResponse)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/{workspaceId}")
    @Operation(summary = "Create or optimistically update an ad-hoc analysis draft")
    public ResponseEntity<AnalysisDraftView> save(
            @PathVariable String workspaceId,
            @RequestBody SaveAnalysisDraftRequest requestBody,
            HttpServletRequest request) {
        requireMatchingExplicitPin(request, workspaceId);
        AnalysisDraftView view = service.save(
                workspaceResolver.resolveCurrentUsername(), workspaceId, requestBody);
        return versionedResponse(view);
    }

    @DeleteMapping("/{workspaceId}")
    @Operation(summary = "Discard the current user's ad-hoc analysis draft")
    public ResponseEntity<Void> delete(
            @PathVariable String workspaceId,
            @RequestParam(required = false) Long expectedVersion,
            HttpServletRequest request) {
        requireMatchingExplicitPin(request, workspaceId);
        service.delete(
                workspaceResolver.resolveCurrentUsername(), workspaceId, expectedVersion);
        return ResponseEntity.noContent().build();
    }

    private static void requireMatchingExplicitPin(
            HttpServletRequest request, String workspaceId) {
        String explicitWorkspaceId = request.getHeader(
                WorkspaceContextResolver.WORKSPACE_HEADER);
        if (!hasText(explicitWorkspaceId)) {
            explicitWorkspaceId = request.getParameter(
                    WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER);
        }
        if (hasText(explicitWorkspaceId)
                && !explicitWorkspaceId.strip().equals(workspaceId.strip())) {
            throw new AccessDeniedException(
                    "Analysis draft workspace does not match the pinned request context");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ResponseEntity<AnalysisDraftView> versionedResponse(AnalysisDraftView view) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag("\"" + view.version() + "\"")
                .body(view);
    }
}
