package com.taxonomy.portfolio.controller;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/** Git projection, semantic merge and materialization for project portfolios. */
@RestController
@RequestMapping("/api/projects/git")
@Tag(name = "Project Portfolio Git")
public class PortfolioGitController {

    private final PortfolioGitService portfolioGitService;
    private final SemanticGitMergeService semanticMergeService;
    private final DslGitRepositoryFactory repositoryFactory;
    private final WorkspaceResolver workspaceResolver;
    private final RepositoryStateService repositoryStateService;

    public PortfolioGitController(PortfolioGitService portfolioGitService,
                                  SemanticGitMergeService semanticMergeService,
                                  DslGitRepositoryFactory repositoryFactory,
                                  WorkspaceResolver workspaceResolver,
                                  RepositoryStateService repositoryStateService) {
        this.portfolioGitService = portfolioGitService;
        this.semanticMergeService = semanticMergeService;
        this.repositoryFactory = repositoryFactory;
        this.workspaceResolver = workspaceResolver;
        this.repositoryStateService = repositoryStateService;
    }

    @Operation(summary = "Export the Git-backed project portfolio DSL")
    @GetMapping(value = "/export", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> exportPortfolio() {
        RequestContext request = context();
        return ResponseEntity.ok(portfolioGitService.exportPortfolio(
                request.username(), request.workspaceContext()));
    }

    @Operation(summary = "Commit current project and requirement state into the architecture DSL")
    @PostMapping("/commit")
    public ResponseEntity<PortfolioGitService.CommitResult> commit(
            @RequestParam(defaultValue = "draft") String branch,
            @RequestBody(required = false) CommitRequest request) throws IOException {
        RequestContext current = context();
        String message = request != null ? request.message() : null;
        return ResponseEntity.ok(portfolioGitService.commit(
                branch, message, current.username(), current.workspaceContext()));
    }

    @Operation(summary = "Materialize project and requirement blocks from a branch HEAD")
    @PostMapping("/materialize")
    public ResponseEntity<PortfolioGitService.MaterializeResult> materialize(
            @RequestParam(defaultValue = "draft") String branch) throws IOException {
        RequestContext current = context();
        return ResponseEntity.ok(portfolioGitService.materializeHead(
                branch, current.username(), current.workspaceContext()));
    }

    @Operation(summary = "Semantically merge two architecture branches and materialize the portfolio")
    @PostMapping("/merge")
    public ResponseEntity<MergeResponse> merge(@RequestBody MergeRequest request) throws IOException {
        if (request == null || blank(request.fromBranch()) || blank(request.intoBranch())) {
            return ResponseEntity.badRequest().body(new MergeResponse(
                    false, null, false, List.of("fromBranch and intoBranch are required"), null));
        }
        RequestContext current = context();
        DslGitRepository repository = repositoryFactory.resolveRepository(current.workspaceContext());
        SemanticGitMergeService.MergeOutcome outcome = semanticMergeService.mergeBranches(
                repository,
                request.fromBranch().strip(),
                request.intoBranch().strip(),
                current.username());
        if (!outcome.success()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MergeResponse(
                    false, null, outcome.semanticFallback(), outcome.conflicts(), null));
        }
        PortfolioGitService.MaterializeResult materialized = portfolioGitService.materializeHead(
                request.intoBranch().strip(), current.username(), current.workspaceContext());
        return ResponseEntity.ok(new MergeResponse(
                true, outcome.commitId(), outcome.semanticFallback(), List.of(), materialized));
    }

    private RequestContext context() {
        String username = workspaceResolver.resolveCurrentUsername();
        try {
            repositoryStateService.ensureWorkspaceState(username);
            return new RequestContext(username, workspaceResolver.resolveCurrentContext());
        } catch (RuntimeException exception) {
            return new RequestContext(username, WorkspaceContext.SHARED);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record CommitRequest(String message) {
    }

    public record MergeRequest(String fromBranch, String intoBranch) {
    }

    public record MergeResponse(
            boolean success,
            String commitId,
            boolean semanticFallback,
            List<String> conflicts,
            PortfolioGitService.MaterializeResult materialized) {
        public MergeResponse {
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }
    }

    private record RequestContext(String username, WorkspaceContext workspaceContext) {
    }
}
