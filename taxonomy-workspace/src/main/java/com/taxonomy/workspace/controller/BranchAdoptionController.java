package com.taxonomy.workspace.controller;

import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.workspace.service.*;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/workspace/adoption-rule")
public class BranchAdoptionController {
    private final WorkspaceResolver contexts;
    private final ContextNavigationService navigation;
    private final BranchAdoptionService rules;
    private final BranchAdoptionWorker worker;

    public BranchAdoptionController(WorkspaceResolver contexts, ContextNavigationService navigation,
            BranchAdoptionService rules, BranchAdoptionWorker worker) {
        this.contexts = contexts;
        this.navigation = navigation;
        this.rules = rules;
        this.worker = worker;
    }

    public record Configuration(Boolean enabled, Long expectedRevision) {
    }

    public record Decision(String action, String sourceCommit, Long expectedRevision) {
    }

    @GetMapping
    @Operation(summary = "Inspect the selected branch’s automatic adoption rule", description = "Returns the explicit opt-in, source default branch and last audited result for the selected repository, workspace and branch.")
    public ResponseEntity<BranchAdoptionRule.View> inspect(@RequestParam String repositoryId,
            @RequestParam String workspaceId, @RequestParam String branch) {
        return privateResponse(rules.inspect(selected(repositoryId, workspaceId, branch)));
    }

    @GetMapping("/history")
    @Operation(summary = "Read branch adoption history", description = "Returns the fifty latest rule changes and automatic outcomes for the selected target, including source and target commits.")
    public ResponseEntity<List<BranchAdoptionService.Event>> history(@RequestParam String repositoryId,
            @RequestParam String workspaceId, @RequestParam String branch) {
        return privateResponse(rules.history(selected(repositoryId, workspaceId, branch)));
    }

    @PutMapping
    @Operation(summary = "Configure adoption of future default-branch commits", description = "Activates or stops the independent rule at its reviewed revision. A personal working copy also requires an editable context ID.")
    public ResponseEntity<BranchAdoptionRule.View> configure(@RequestParam String repositoryId,
            @RequestParam String workspaceId,
            @RequestParam String branch, @RequestParam(required = false) String expectedContextId,
            @RequestBody Configuration configuration) throws IOException {
        if (configuration.expectedRevision() == null || configuration.enabled() == null)
            throw new IllegalArgumentException("A rule revision and enabled flag are required");
        var selected = selected(repositoryId, workspaceId, branch);
        return privateResponse(write(selected, expectedContextId,
                () -> rules.configure(selected, configuration.enabled(), configuration.expectedRevision())));
    }

    @PostMapping("/decision")
    @Operation(summary = "Review a stopped default-branch commit", description = "Retry or skip only the exact displayed source commit at the selected target and rule revision.")
    public ResponseEntity<BranchAdoptionRule.View> decide(@RequestParam String repositoryId,
            @RequestParam String workspaceId,
            @RequestParam String branch, @RequestParam(required = false) String expectedContextId,
            @RequestBody Decision request) throws IOException {
        if (request.expectedRevision() == null || request.sourceCommit() == null)
            throw new IllegalArgumentException("A rule revision and source commit are required");
        var selected = selected(repositoryId, workspaceId, branch);
        return privateResponse(write(selected, expectedContextId, () -> {
            var admitted = rules.decide(selected, request.action(), request.sourceCommit(), request.expectedRevision());
            return "RETRY".equals(request.action()) ? worker.retryReviewed(selected, admitted, request.sourceCommit())
                    : admitted;
        }));
    }

    private RepositoryContext selected(String repository, String workspace, String branch) {
        var selected = contexts.resolveUnambiguousRepositoryForRead();
        if (!repository.equals(selected.repositoryId())
                || !Objects.equals(workspace.isEmpty() ? null : workspace, selected.workspaceId())
                || !branch.equals(selected.branch()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The selected repository, workspace or branch changed");
        return selected.workspaceId() == null ? RepositoryContext.centralWrite(repository, branch, selected.username())
                : selected;
    }

    private BranchAdoptionRule.View write(RepositoryContext c, String context,
            WorkspaceArchitectureVersionPort.GitAction<BranchAdoptionRule.View> action) throws IOException {
        if (c.workspaceId() == null)
            return action.run();
        if (context == null || context.isBlank())
            throw new IllegalArgumentException("An editable workspace context is required");
        return navigation.withEditableBranch(WorkspaceResolver.workspaceContext(c), context, action::run);
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalid() {
    }

    @ExceptionHandler({ IllegalStateException.class, BranchHeadConflictException.class })
    @ResponseStatus(HttpStatus.CONFLICT)
    void changed() {
    }
}
