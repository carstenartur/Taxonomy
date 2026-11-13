package com.taxonomy.workspace.service;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.regex.Pattern;

/** Confirmation of a destructive variant operation belongs to one working copy, ref and exact Git object. */
@Service
public class WorkspaceBranchDeletion {
    public interface Actor {
        String require(String username);
    }
    public record Preview(String branch, String head, String etag) {}

    private static final Pattern ETAG = Pattern.compile("\"variant-([0-9a-f]{64})-([0-9a-f]{40})\"");
    private final UserWorkspaceRepository workspaces;
    private final SystemRepositoryService repositories;
    private final RepositoryMembershipService memberships;
    private final DslGitRepositoryFactory stores;
    private final Actor actor;

    public WorkspaceBranchDeletion(UserWorkspaceRepository workspaces, SystemRepositoryService repositories,
            RepositoryMembershipService memberships, DslGitRepositoryFactory stores, Actor actor) {
        this.workspaces = workspaces;
        this.repositories = repositories;
        this.memberships = memberships;
        this.stores = stores;
        this.actor = actor;
    }

    @Transactional(readOnly = true)
    public Preview preview(RepositoryContext selection, String name) throws IOException {
        authorize(selection, name, false);
        String head = stores.resolveRepository(selection).getHeadCommit(name);
        if (head == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Variant unavailable");
        return new Preview(name, head, etag(selection, name, head));
    }

    @Transactional(rollbackFor = IOException.class)
    public void delete(RepositoryContext selection, String name, String ifMatch) throws IOException {
        String actorId = authorize(selection, name, true);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "Review the variant before deleting it");
        }
        var match = ETAG.matcher(ifMatch);
        if (!match.matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One exact variant ETag is required");
        String head = match.group(2);
        if (!ifMatch.equals(etag(selection, name, head))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "The selected variant changed");
        }
        stores.resolveRepository(selection).deleteBranch(name, head, actorId);
    }

    private String authorize(RepositoryContext selection, String name, boolean mutation) {
        if (selection.scope() != RepositoryScope.WORKSPACE) throw denied();
        if (mutation) {
            repositories.lock(selection.repositoryId());
            memberships.lockForRefMutation(selection.repositoryId());
        }
        var workspace = (mutation ? workspaces.findByWorkspaceIdForUpdate(selection.workspaceId())
                : workspaces.findByWorkspaceId(selection.workspaceId())).orElseThrow(WorkspaceBranchDeletion::denied);
        var repository = repositories.getRepository(selection.repositoryId());
        String user = selection.username();
        if (workspace.getProvisioningStatus() != WorkspaceProvisioningStatus.READY
                || !selection.branch().equals(workspace.getCurrentBranch())
                || !memberships.canUseWorkspace(repository, workspace, user)
                || !memberships.canContribute(repository, user)
                || (!user.equals(workspace.getUsername()) && !memberships.canMaintain(repository, user))) throw denied();
        String actorId = actor.require(user);
        if (name == null || !Repository.isValidRefName(Constants.R_HEADS + name)) throw new IllegalArgumentException("Invalid branch");
        if (name.equals("draft") || name.equals("accepted") || name.equals("main") || name.equals(selection.branch())) {
            throw new IllegalArgumentException("Cannot delete protected branch: " + name);
        }
        return actorId;
    }

    private static String etag(RepositoryContext selection, String name, String head) {
        String ref = StableIdentityHash.sha256(selection.repositoryWorkspaceScopeKey() + ":" + name);
        return "\"variant-" + ref + "-" + head + "\"";
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Variant deletion is unavailable in this working copy");
    }
}
