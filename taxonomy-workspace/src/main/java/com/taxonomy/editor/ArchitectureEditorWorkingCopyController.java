package com.taxonomy.editor;

import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryWorkspaceService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The editor's working-copy command is available independently of the repository catalogue UI/API. */
@RestController
@Tag(name = "Architecture editor")
public class ArchitectureEditorWorkingCopyController {
    private final WorkspaceResolver resolver;
    private final RepositoryMembershipService membership;
    private final RepositoryWorkspaceService workspaces;
    private final SystemRepositoryService repositories;

    public ArchitectureEditorWorkingCopyController(WorkspaceResolver resolver,
            RepositoryMembershipService membership, RepositoryWorkspaceService workspaces,
            SystemRepositoryService repositories) {
        this.resolver = resolver;
        this.membership = membership;
        this.workspaces = workspaces;
        this.repositories = repositories;
    }

    @PostMapping("/api/architecture/editor/workspaces")
    @Operation(summary = "Create an editor working copy from the selected central branch",
            description = "Requires the exact currently selected central repository and branch and repository working-copy permission "
                    + "(CONTRIBUTOR, with the existing authenticated-user exception for the historic primary repository). "
                    + "Creates a personal working copy through the existing provisioning service. Does not switch other tabs "
                    + "or adopt an analysis snapshot; the multi-repository catalogue feature flag is not required.")
    public ResponseEntity<WorkingCopy> create(@RequestBody CreateRequest request) {
        var source = resolver.resolveCurrentRepositoryContext();
        if (!source.repositoryId().equals(request.repositoryId()) || !source.branch().equals(request.branch())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The exact source context is not selected or accessible");
        }
        if (source.workspaceId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Select a central architecture before creating a working copy");
        }
        if (!membership.canContribute(repositories.getRepository(source.repositoryId()), source.username())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Repository CONTRIBUTOR role required");
        }
        if (request.displayName() == null || request.displayName().isBlank() || request.displayName().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A working-copy name of 1–255 characters is required");
        }
        var workspace = workspaces.createWorkingCopy(source.username(), source.repositoryId(),
                source.branch(), request.displayName().strip(), "");
        return ResponseEntity.status(HttpStatus.CREATED).body(new WorkingCopy(workspace.getWorkspaceId(),
                workspace.getSourceRepositoryId(), workspace.getSourceBranch(), workspace.getCurrentBranch(),
                workspace.getProvisioningStatus()));
    }

    public record CreateRequest(String repositoryId, String branch, String displayName) {}
    public record WorkingCopy(String workspaceId, String sourceRepositoryId, String sourceBranch,
                              String currentBranch, WorkspaceProvisioningStatus provisioningStatus) {}
}
