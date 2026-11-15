package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A read-only ancestry plan against the last fetched upstream commit. Content conflicts are not evaluated. */
public record UpstreamRebasePlan(String repositoryId, String workspaceId, String branch, String localCommit,
                                 WorkspaceUpstreamService.Status upstream, State status, String mergeBase,
                                 List<Commit> commits) {
    public enum State { NO_LOCAL_BRANCH, NO_TRACKING, UNRELATED, AMBIGUOUS_BASE, NON_LINEAR, TOO_LONG,
                        UP_TO_DATE, FAST_FORWARD, REPLAY }
    public record Commit(String id, String subject) {}

    private static final int MAX_REPLAY_COMMITS = 200;

    static UpstreamRebasePlan read(GitUpstreamTransfer transfer, String repositoryId, String workspaceId,
                                  String branch) throws IOException {
        if (branch == null || !Repository.isValidRefName(Constants.R_HEADS + branch)
                || branch.startsWith(DslGitRepository.EXTERNAL_SYNC_BRANCH_PREFIX)) {
            throw new IllegalArgumentException("An exact local branch is required");
        }
        var upstream = transfer.inspect();
        var target = transfer.target();
        var local = target.readFreshRef(Constants.R_HEADS + branch);
        String head = local == null || local.isSymbolic() || local.getObjectId() == null ? null : local.getObjectId().name();
        var plan = new UpstreamRebasePlan(repositoryId, workspaceId, branch, head, upstream,
                head == null ? State.NO_LOCAL_BRANCH : State.NO_TRACKING, null, List.of());
        if (head != null && upstream.trackedCommit() != null) {
            plan = plan.inspectHistory(target.getGitRepository());
        }
        transfer.reauthorize().run();
        return plan;
    }

    private UpstreamRebasePlan inspectHistory(Repository repository) throws IOException {
        try (var walk = new RevWalk(repository)) {
            var local = walk.parseCommit(ObjectId.fromString(localCommit));
            var tracked = walk.parseCommit(ObjectId.fromString(upstream.trackedCommit()));
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(local);
            walk.markStart(tracked);
            var base = walk.next();
            if (base == null) return result(State.UNRELATED, null, List.of());
            if (walk.next() != null) return result(State.AMBIGUOUS_BASE, null, List.of());
            if (tracked.equals(base)) return result(State.UP_TO_DATE, base, List.of());
            if (local.equals(base)) return result(State.FAST_FORWARD, base, List.of());
            var candidates = new ArrayList<Commit>();
            for (var current = local; !current.equals(base); current = walk.parseCommit(current.getParent(0))) {
                if (current.getParentCount() != 1) return result(State.NON_LINEAR, base, List.of());
                if (candidates.size() == MAX_REPLAY_COMMITS) return result(State.TOO_LONG, base, List.of());
                walk.parseBody(current);
                String subject = current.getShortMessage();
                candidates.add(new Commit(current.name(), subject.length() <= 160 ? subject : subject.substring(0, 160) + "…"));
            }
            return result(State.REPLAY, base, List.copyOf(candidates.reversed()));
        }
    }

    private UpstreamRebasePlan result(State state, RevCommit base, List<Commit> commits) {
        return new UpstreamRebasePlan(repositoryId, workspaceId, branch, localCommit, upstream,
                state, base == null ? null : base.name(), commits);
    }
}
