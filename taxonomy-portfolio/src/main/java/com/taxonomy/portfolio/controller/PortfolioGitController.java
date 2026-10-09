package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.PortfolioGitDtos.CommitPortfolioRequest;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.ExportedPortfolioDsl;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.MaterializationPreview;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.MaterializePortfolioRequest;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.MaterializePortfolioResult;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.MergePortfolioRequest;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.MergePortfolioResult;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.PortfolioCommitResult;
import com.taxonomy.portfolio.service.PortfolioGitApplicationService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Git projection, reviewed materialization and semantic merge for project portfolios. */
@RestController
@RequestMapping("/api/projects/git")
@Tag(name = "Project Portfolio Git")
public class PortfolioGitController {

    private final PortfolioGitApplicationService gitService;
    private final WorkspaceResolver workspaceResolver;
    private final RepositoryStateService repositoryStateService;

    public PortfolioGitController(PortfolioGitApplicationService gitService,
                                  WorkspaceResolver workspaceResolver,
                                  RepositoryStateService repositoryStateService) {
        this.gitService = gitService;
        this.workspaceResolver = workspaceResolver;
        this.repositoryStateService = repositoryStateService;
    }

    @Operation(summary = "Export the current Git-backed portfolio projection",
            description = "Exports the current workspace portfolio as DSL. Returns plain text by default; an explicitly compatible application/json Accept header selects the structured exported-DSL representation. Does not create a Git commit.")
    @GetMapping(value = "/export",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<?> exportPortfolio(
            @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String accept)
            throws IOException {
        ExportedPortfolioDsl exported = gitService.export(context().workspaceContext());
        if (explicitlyRequestsJson(accept)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(exported);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(exported.dsl());
    }

    @Operation(summary = "Commit the reviewed current portfolio projection",
            description = "Serializes and commits the current portfolio in the authenticated workspace. A body branch overrides the query branch; otherwise the workspace branch is used. Returns commit and materialization information, not an AI analysis.")
    @PostMapping("/commit")
    public PortfolioCommitResult commit(
            @RequestParam(required = false) String branch,
            @RequestBody(required = false) CommitPortfolioRequest request) throws IOException {
        RequestContext current = context();
        String effectiveBranch = firstNonBlank(
                request != null ? request.branch() : null,
                branch,
                current.workspaceContext().currentBranch(),
                "draft");
        return gitService.commit(
                effectiveBranch,
                request != null ? request.message() : null,
                current.workspaceContext());
    }

    @Operation(summary = "Preview branch materialization without changing portfolio data",
            description = "Previews the portfolio represented by the selected Git branch in the current workspace. This read does not apply portfolio changes; materialization requires a separate explicit command with an expected head.")
    @GetMapping("/materialize-preview")
    public MaterializationPreview previewMaterialize(
            @RequestParam(defaultValue = "draft") String branch) throws IOException {
        return gitService.previewMaterialize(branch, context().workspaceContext());
    }

    @Operation(summary = "Materialize one reviewed branch HEAD into the portfolio",
            description = "Applies the portfolio from the selected branch only after checking the caller's expected Git head. This explicit write is separate from the read-only materialization preview and rejects a changed source.")
    @PostMapping("/materialize")
    public MaterializePortfolioResult materialize(
            @RequestParam(required = false) String branch,
            @RequestBody(required = false) MaterializePortfolioRequest request) throws IOException {
        RequestContext current = context();
        String effectiveBranch = firstNonBlank(
                request != null ? request.branch() : null,
                branch,
                current.workspaceContext().currentBranch(),
                "draft");
        return gitService.materialize(
                effectiveBranch,
                request != null ? request.expectedHead() : null,
                current.workspaceContext());
    }

    @Operation(summary = "Semantically merge two branches and materialize the target portfolio",
            description = "Semantically merges the requested source into the target portfolio branch and materializes the target result. Branches may be supplied in the body or query, with body values taking precedence; conflicts are not silently discarded.")
    @PostMapping("/merge")
    public MergePortfolioResult merge(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String target,
            @RequestBody(required = false) MergePortfolioRequest request) throws IOException {
        RequestContext current = context();
        String effectiveSource = firstNonBlank(
                request != null ? request.sourceBranch() : null,
                source);
        String effectiveTarget = firstNonBlank(
                request != null ? request.targetBranch() : null,
                target,
                current.workspaceContext().currentBranch(),
                "draft");
        return gitService.merge(
                effectiveSource,
                effectiveTarget,
                request != null ? request.message() : null,
                current.workspaceContext());
    }

    /** Resolve the authenticated workspace once and fail closed on provisioning errors. */
    private RequestContext context() {
        String username = workspaceResolver.resolveCurrentUsername();
        repositoryStateService.ensureWorkspaceState(username);
        return new RequestContext(username, workspaceResolver.resolveCurrentContext());
    }

    private static boolean explicitlyRequestsJson(String accept) {
        if (accept == null || accept.isBlank()) return false;
        try {
            return MediaType.parseMediaTypes(accept).stream()
                    .filter(type -> type.getQualityValue() > 0.0d)
                    .filter(type -> !type.isWildcardType() && !type.isWildcardSubtype())
                    .anyMatch(MediaType.APPLICATION_JSON::isCompatibleWith);
        } catch (IllegalArgumentException invalidAcceptHeader) {
            return false;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) return value.strip();
            }
        }
        return null;
    }

    private record RequestContext(String username, WorkspaceContext workspaceContext) {
    }
}
