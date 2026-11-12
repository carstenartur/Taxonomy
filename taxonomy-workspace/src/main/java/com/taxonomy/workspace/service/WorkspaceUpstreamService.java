package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

/** Fetch an exact upstream commit into a personal working copy without integrating it. */
@Service
public class WorkspaceUpstreamService {
    public record Status(String sourceRepositoryId, String sourceBranch, String sourceCommit,
                         String trackingRef, String trackedCommit) {}
    public record TagStatus(String name, String sourceTagObject, String destinationTagObject, String trackedCommit) {}
    public record TagRequest(String name, String sourceTagObject, String trackedCommit) {
        public TagRequest {
            sourceTagObject = objectId(sourceTagObject);
            trackedCommit = objectId(trackedCommit);
        }
    }
    public record Request(String sourceCommit, String expectedTrackedCommit) {
        public Request {
            sourceCommit = objectId(sourceCommit);
            if (expectedTrackedCommit != null) expectedTrackedCommit = objectId(expectedTrackedCommit);
        }
    }
    private static String objectId(String value) {
        if (value == null || !ObjectId.isId(value)) throw new IllegalArgumentException("A complete Git object ID is required");
        return ObjectId.fromString(value).name();
    }

    record Capture(Status source, String forkPoint) { }

    @Transactional(readOnly = true)
    public Capture capture(RepositoryContext context) throws IOException {
        var transfer = open(context, false).transfer();
        return new Capture(transfer.inspect(), transfer.forkPoint());
    }

    private record WorkingCopy(UserWorkspace workspace, GitUpstreamTransfer transfer) {}

    private final UserWorkspaceRepository rows;
    private final SystemRepositoryService catalogue;
    private final RepositoryMembershipService memberships;
    private final DslGitRepositoryFactory repositories;

    public WorkspaceUpstreamService(UserWorkspaceRepository rows, SystemRepositoryService catalogue,
                                    RepositoryMembershipService memberships, DslGitRepositoryFactory repositories) {
        this.rows = rows;
        this.catalogue = catalogue;
        this.memberships = memberships;
        this.repositories = repositories;
    }

    @Transactional(readOnly = true)
    public Status inspect(RepositoryContext selected) throws IOException {
        return open(selected, false).transfer().inspect();
    }

    @Transactional(rollbackFor = IOException.class)
    Status fetch(RepositoryContext selected, Request request) throws IOException {
        return fetchRecorded(selected, null, null, request, false, () -> { });
    }

    @Transactional(rollbackFor = IOException.class)
    public Status fetchRecorded(RepositoryContext selected, String sourceBranch, String forkPoint, Request request, boolean recovery, Runnable fence) throws IOException {
        var copy = open(selected, true);
        if (sourceBranch != null && (!sourceBranch.equals(copy.transfer().upstream().branch())
                || !java.util.Objects.equals(forkPoint, copy.transfer().forkPoint()))) {
            throw new BranchHeadConflictException(sourceBranch, request.sourceCommit(), null, "The working copy upstream changed");
        }
        var status = recovery ? copy.transfer().recover(request, fence) : copy.transfer().fetch(request, fence);
        copy.workspace().setLastFetchedCommit(status.trackedCommit());
        rows.save(copy.workspace());
        return status;
    }

    @Transactional(readOnly = true)
    public TagStatus inspectTag(RepositoryContext selected, String name) throws IOException {
        return open(selected, false).transfer().inspectTag(name);
    }

    @Transactional(rollbackFor = IOException.class)
    public TagStatus fetchTag(RepositoryContext selected, TagRequest request) throws IOException {
        return open(selected, true).transfer().fetchTag(request);
    }

    private WorkingCopy open(RepositoryContext selected, boolean forUpdate) {
        if (selected.scope() != RepositoryScope.WORKSPACE) throw unavailable();
        var workspace = (forUpdate ? rows.findByWorkspaceIdForUpdate(selected.workspaceId())
                : rows.findByWorkspaceId(selected.workspaceId())).orElseThrow(WorkspaceUpstreamService::unavailable);
        if (!selected.username().equals(workspace.getUsername()) || workspace.isArchived() || workspace.isShared()
                || workspace.getProvisioningStatus() != WorkspaceProvisioningStatus.READY
                || !selected.repositoryId().equals(workspace.getSourceRepositoryId())) throw unavailable();
        requireSource(selected.repositoryId(), selected.username());
        var upstream = new GitUpstreamRef(workspace.getSourceRepositoryId(),
                workspace.resolveUpstreamBranch(catalogue.getRepository(selected.repositoryId())));
        return new WorkingCopy(workspace, new GitUpstreamTransfer(upstream,
                repositories.getCentralRepository(upstream.repositoryId()),
                repositories.openWorkspaceRepository(workspace.getWorkspaceId()), workspace.getBaseCommit(),
                selected.username(), () -> requireSource(upstream.repositoryId(), selected.username())));
    }

    private void requireSource(String repository, String actor) {
        catalogue.readCurrentAuthorization(repository, current -> {
            if (!memberships.canContribute(current, actor)) throw unavailable();
        });
    }

    private static AccessDeniedException unavailable() {
        return new AccessDeniedException("The selected working copy or source is unavailable");
    }
}
