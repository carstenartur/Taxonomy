package com.taxonomy.workspace.controller;

import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.PersonalWorkspaceRebase;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.UpstreamRebasePlan;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.storage.DslGitRepository;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@RestController
@RequestMapping("/api/workspace/upstream/rebase")
public class PersonalWorkspaceRebaseController {
    private final WorkspaceResolver resolver;
    private final ContextNavigationService navigation;
    private final PersonalWorkspaceRebase rebases;

    public PersonalWorkspaceRebaseController(WorkspaceResolver resolver, ContextNavigationService navigation, PersonalWorkspaceRebase rebases) {
        this.resolver = resolver;
        this.navigation = navigation;
        this.rebases = rebases;
    }

    @PostMapping
    @Operation(summary = "Replay the reviewed personal branch onto its fetched upstream",
            description = "Checks the pinned repository, owned working copy, editable browser context, branch and reviewed refs. "
                    + "Returns the original and replacement commit identities or the model conflict requiring a decision.")
    public DslGitRepository.RebaseReplay apply(@RequestParam String repositoryId, @RequestParam String workspaceId,
            @RequestParam String branch, @RequestParam String expectedContextId, @RequestBody UpstreamRebasePlan.ApplyRequest request) throws IOException {
        var selected = selection(repositoryId, workspaceId, branch);
        return navigation.withEditableBranch(WorkspaceResolver.workspaceContext(selected), expectedContextId, () -> rebases.apply(selected, request));
    }

    public record Recovery(String candidate, String expectedHead) {}

    @PostMapping("/resume")
    @Operation(summary = "Finish adopting an interrupted personal rebase",
            description = "Checks the retained replay and explicitly reviewed current branch head before reconciling "
                    + "the editor, portfolio and workspace metadata. The branch remains at that head.")
    public DslGitRepository.RebaseReplay resume(@RequestParam String repositoryId, @RequestParam String workspaceId,
            @RequestParam String branch, @RequestParam String expectedContextId, @RequestBody Recovery recovery) throws IOException {
        var selected = selection(repositoryId, workspaceId, branch);
        return navigation.withEditableBranch(WorkspaceResolver.workspaceContext(selected), expectedContextId, () -> rebases.resume(selected, recovery.candidate(), recovery.expectedHead()));
    }

    private RepositoryContext selection(String repositoryId, String workspaceId, String branch) {
        var selected = resolver.resolveUnambiguousRepositoryForRead();
        if (selected.scope() != RepositoryScope.WORKSPACE || !repositoryId.equals(selected.repositoryId())
                || !workspaceId.equals(selected.workspaceId()) || !branch.equals(selected.branch()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The reviewed workspace or branch changed");
        return selected;
    }


    @ExceptionHandler(BranchHeadConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    void changed() {}

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalid() {}
}
