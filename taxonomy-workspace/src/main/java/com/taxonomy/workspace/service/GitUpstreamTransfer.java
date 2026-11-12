package com.taxonomy.workspace.service;

import com.taxonomy.workspace.service.WorkspaceUpstreamService.Request;
import com.taxonomy.workspace.service.WorkspaceUpstreamService.Status;
import com.taxonomy.workspace.service.WorkspaceUpstreamService.TagRequest;
import com.taxonomy.workspace.service.WorkspaceUpstreamService.TagStatus;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import com.taxonomy.workspace.storage.GitSelectedTag;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Exact-source fetch and selected-tag publication shared by working copies and central forks. */
record GitUpstreamTransfer(GitUpstreamRef upstream, DslGitRepository source, DslGitRepository target,
                           String forkPoint, String actor, Runnable reauthorize) {
    Status inspect() throws IOException {
        return new Status(upstream.repositoryId(), upstream.branch(), sourceHead(),
                upstream.name(), upstream.head(target));
    }

    Status fetch(Request request) throws IOException {
        String incoming = request.sourceCommit();
        String tracked = upstream.head(target);
        if (!Objects.equals(tracked, request.expectedTrackedCommit()) && !incoming.equals(tracked)) {
            throw new BranchHeadConflictException(upstream.name(), request.expectedTrackedCommit(), tracked, "The tracked commit changed");
        }
        String head = sourceHead();
        if (head == null || !reachable(source, incoming, head)) {
            throw new BranchHeadConflictException(upstream.branch(), incoming, head, "The requested commit is no longer on the source branch");
        }
        if (tracked == null) {
            if (forkPoint == null || !reachable(source, forkPoint, incoming)) {
                throw new BranchHeadConflictException(upstream.branch(), forkPoint, incoming, "Upstream history diverged from the retained fork point");
            }
        }
        // An identical replay may follow a successful ref update whose acknowledgement was lost.
        if (!incoming.equals(tracked)) {
            GitHistoryTransfer.copyCommitClosure(source, target, incoming, tracked == null ? List.of() : List.of(tracked));
        }
        reauthorize.run();
        String currentHead = sourceHead();
        if (!head.equals(currentHead)) {
            throw new BranchHeadConflictException(upstream.branch(), head, currentHead, "Source branch changed during transfer");
        }
        upstream.advance(target, tracked, incoming, actor);
        return new Status(upstream.repositoryId(), upstream.branch(), head, upstream.name(), incoming);
    }

    TagStatus inspectTag(String name) throws IOException {
        String tracked = upstream.head(target);
        var tag = selectedTag(name, tracked);
        return new TagStatus(name, tag == null ? null : tag.objectId(), GitSelectedTag.objectAt(target, name), tracked);
    }

    TagStatus fetchTag(TagRequest request) throws IOException {
        String tracked = upstream.head(target);
        if (!request.trackedCommit().equals(tracked)) {
            throw new BranchHeadConflictException(upstream.name(), request.trackedCommit(), tracked, "Tracking ref changed");
        }
        var tag = selectedTag(request.name(), tracked);
        if (tag == null || !request.sourceTagObject().equals(tag.objectId())) {
            throw new BranchHeadConflictException(request.name(), request.sourceTagObject(), null, "Selected source tag changed");
        }
        reauthorize.run();
        if (!tag.objectId().equals(GitSelectedTag.objectAt(source, request.name()))) {
            throw new BranchHeadConflictException(request.name(), tag.objectId(), null, "Selected source tag changed");
        }
        synchronized (target.getGitRepository()) {
            String current = upstream.head(target);
            if (!tracked.equals(current)) {
                throw new BranchHeadConflictException(upstream.name(), tracked, current, "Tracking ref changed");
            }
            tag.publish(target, actor);
        }
        return new TagStatus(request.name(), tag.objectId(), tag.objectId(), tracked);
    }

    private String sourceHead() throws IOException {
        var ref = source.readFreshRef(Constants.R_HEADS + upstream.branch());
        return ref == null || ref.getObjectId() == null ? null : ref.getObjectId().name();
    }

    private GitSelectedTag selectedTag(String name, String tracked) throws IOException {
        var tag = GitSelectedTag.read(source, name);
        if (tag == null || tracked == null || !reachable(target, tag.commit(), tracked)) return null;
        return tag;
    }

    private static boolean reachable(DslGitRepository repository, String ancestor, String tip) throws IOException {
        try {
            return repository.isAncestor(ancestor, tip);
        } catch (MissingObjectException | IncorrectObjectTypeException unavailable) {
            return false;
        }
    }
}
