package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import com.taxonomy.workspace.storage.GitSelectedTag;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

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

    private record WorkingCopy(UserWorkspace workspace, GitUpstreamRef upstream, DslGitRepository source,
                               DslGitRepository target) {}

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
        var copy = open(selected, false);
        var upstream = copy.upstream();
        return new Status(upstream.repositoryId(), upstream.branch(), sourceHead(copy),
                upstream.name(), upstream.head(copy.target()));
    }

    @Transactional(rollbackFor = IOException.class)
    public Status fetch(RepositoryContext selected, Request request) throws IOException {
        var copy = open(selected, true);
        var workspace = copy.workspace();
        var upstream = copy.upstream();
        var source = copy.source();
        var target = copy.target();
        String incoming = request.sourceCommit();
        String tracked = upstream.head(target);
        if (!Objects.equals(tracked, request.expectedTrackedCommit()) && !incoming.equals(tracked)) {
            throw new BranchHeadConflictException(upstream.name(), request.expectedTrackedCommit(), tracked, "The tracked commit changed");
        }
        String head = sourceHead(copy);
        if (head == null || !reachable(source, incoming, head)) {
            throw new BranchHeadConflictException(upstream.branch(), incoming, head, "The requested commit is no longer on the source branch");
        }
        if (tracked == null) {
            String base = workspace.getBaseCommit();
            if (base == null || !reachable(source, base, incoming)) {
                throw new BranchHeadConflictException(upstream.branch(), base, incoming, "Upstream history diverged from the working copy's fork point");
            }
        }
        // A database commit can fail after the Git CAS. Replaying the same request
        // reconciles the metadata with that exact ref without publishing another commit.
        if (!incoming.equals(tracked)) {
            GitHistoryTransfer.copyCommitClosure(source, target, incoming, tracked == null ? List.of() : List.of(tracked));
        }
        requireSource(upstream.repositoryId(), selected.username());
        String currentHead = sourceHead(copy);
        if (!head.equals(currentHead)) {
            throw new BranchHeadConflictException(upstream.branch(), head, currentHead, "Source branch changed during transfer");
        }
        upstream.advance(target, tracked, incoming, selected.username());
        workspace.setLastFetchedCommit(incoming);
        rows.save(workspace);
        return new Status(upstream.repositoryId(), upstream.branch(), head, upstream.name(), incoming);
    }

    @Transactional(readOnly = true)
    public TagStatus inspectTag(RepositoryContext selected, String name) throws IOException {
        var copy = open(selected, false);
        String tracked = copy.upstream().head(copy.target());
        var tag = selectedTag(copy, name, tracked);
        return new TagStatus(name, tag == null ? null : tag.objectId(), GitSelectedTag.objectAt(copy.target(), name), tracked);
    }

    @Transactional(rollbackFor = IOException.class)
    public TagStatus fetchTag(RepositoryContext selected, TagRequest request) throws IOException {
        var copy = open(selected, true);
        String tracked = copy.upstream().head(copy.target());
        if (!request.trackedCommit().equals(tracked)) {
            throw new BranchHeadConflictException(copy.upstream().name(), request.trackedCommit(), tracked, "Tracking ref changed");
        }
        var tag = selectedTag(copy, request.name(), tracked);
        if (tag == null || !request.sourceTagObject().equals(tag.objectId())) {
            throw new BranchHeadConflictException(request.name(), request.sourceTagObject(), null, "Selected source tag changed");
        }
        requireSource(copy.upstream().repositoryId(), selected.username());
        if (!tag.objectId().equals(GitSelectedTag.objectAt(copy.source(), request.name()))) {
            throw new BranchHeadConflictException(request.name(), tag.objectId(), null, "Selected source tag changed");
        }
        tag.publish(copy.target(), selected.username());
        return new TagStatus(request.name(), tag.objectId(), tag.objectId(), tracked);
    }

    private static String sourceHead(WorkingCopy copy) throws IOException {
        var ref = copy.source().readFreshRef(Constants.R_HEADS + copy.upstream().branch());
        return ref == null || ref.getObjectId() == null ? null : ref.getObjectId().name();
    }

    private static GitSelectedTag selectedTag(WorkingCopy copy, String name, String tracked) throws IOException {
        var tag = GitSelectedTag.read(copy.source(), name);
        if (tag == null || tracked == null || !reachable(copy.target(), tag.commit(), tracked)) return null;
        return tag;
    }

    private static boolean reachable(DslGitRepository repository, String ancestor, String tip) throws IOException {
        try {
            return repository.isAncestor(ancestor, tip);
        } catch (MissingObjectException | IncorrectObjectTypeException unavailable) {
            return false;
        }
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
        return new WorkingCopy(workspace, upstream, repositories.getCentralRepository(upstream.repositoryId()),
                repositories.openWorkspaceRepository(workspace.getWorkspaceId()));
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
