package com.taxonomy.workspace.service;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.RebaseDslConflict;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A read-only ancestry plan against the last fetched upstream commit. Content conflicts are not evaluated. */
public record UpstreamRebasePlan(String repositoryId, String workspaceId, String branch, String localCommit,
                                 WorkspaceUpstreamService.Status upstream, State status, String mergeBase,
                                 List<Commit> commits, String recoverableCommit) {
    UpstreamRebasePlan(String repositoryId, String workspaceId, String branch, String localCommit,
                       WorkspaceUpstreamService.Status upstream, State status, String mergeBase, List<Commit> commits) {
        this(repositoryId, workspaceId, branch, localCommit, upstream, status, mergeBase, commits, null);
    }

    UpstreamRebasePlan withRecoverableCommit(String commit) {
        return new UpstreamRebasePlan(repositoryId, workspaceId, branch, localCommit, upstream, status, mergeBase, commits, commit);
    }

    public enum State { NO_LOCAL_BRANCH, NO_TRACKING, UNRELATED, AMBIGUOUS_BASE, NON_LINEAR, TOO_LONG,
                        UP_TO_DATE, FAST_FORWARD, REPLAY }
    public record Commit(String id, String subject) {}

    private static final int MAX_REPLAY_COMMITS = 200;

    private record Refs(String localCommit, WorkspaceUpstreamService.Status upstream) {}

    static UpstreamRebasePlan read(GitUpstreamTransfer transfer, String repositoryId, String workspaceId,
                                  String branch) throws IOException {
        var reauthorize = transfer.reauthorize();
        reauthorize.run();
        try {
            var refs = readRefs(transfer, branch);
            var plan = new UpstreamRebasePlan(repositoryId, workspaceId, branch, refs.localCommit(), refs.upstream(),
                    refs.localCommit() == null ? State.NO_LOCAL_BRANCH : State.NO_TRACKING, null, List.of());
            if (refs.localCommit() != null && refs.upstream().trackedCommit() != null) {
                return plan.inspectHistory(transfer.target().getGitRepository());
            }
            return plan;
        } finally {
            // Recheck authority before releasing either the plan or an inspection error.
            reauthorize.run();
        }
    }

    static String readRefTag(GitUpstreamTransfer transfer, String repositoryId, String workspaceId,
                             String branch) throws IOException {
        var reauthorize = transfer.reauthorize();
        reauthorize.run();
        try {
            return refTag(repositoryId, workspaceId, branch, readRefs(transfer, branch));
        } finally {
            reauthorize.run();
        }
    }

    /** Exact ref, commit and ordered replay identities supplied by an explicit user action. */
    public record ApplyRequest(String expectedRefTag, String expectedLocalCommit,
                               String expectedTrackedCommit, List<String> orderedCommits,
                               Map<String, RebaseDslConflict.Resolution> resolutions) {
        public ApplyRequest(String expectedRefTag, String expectedLocalCommit, String expectedTrackedCommit,
                            List<String> orderedCommits) {
            this(expectedRefTag, expectedLocalCommit, expectedTrackedCommit, orderedCommits, Map.of());
        }

        public ApplyRequest {
            Objects.requireNonNull(expectedRefTag, "expectedRefTag");
            Objects.requireNonNull(expectedLocalCommit, "expectedLocalCommit");
            Objects.requireNonNull(expectedTrackedCommit, "expectedTrackedCommit");
            resolutions = resolutions == null ? Map.of() : Map.copyOf(resolutions);
            if (resolutions.size() > MAX_REPLAY_COMMITS || resolutions.values().stream()
                    .mapToLong(value -> value.text().length()).sum() > 4L * 1024 * 1024) {
                throw new IllegalArgumentException("The conflict resolutions exceed the replay limit");
            }
            orderedCommits = List.copyOf(Objects.requireNonNull(orderedCommits, "orderedCommits"));
        }
    }

    static DslGitRepository.RebaseReplay apply(GitUpstreamTransfer transfer, String repositoryId,
                                               String workspaceId, String branch, ApplyRequest request)
            throws IOException {
        return replay(transfer, repositoryId, workspaceId, branch, request, true);
    }

    static DslGitRepository.RebaseReplay replay(GitUpstreamTransfer transfer, String repositoryId, String workspaceId,
                                                String branch, ApplyRequest request, boolean publish) throws IOException {
        Objects.requireNonNull(request, "request");
        // Invalid request syntax is a 400, but an otherwise valid plan whose refs moved is a 409,
        // even when that movement changed the current plan state from REPLAY to UP_TO_DATE.
        if (!request.expectedRefTag().matches("W/\\\"[0-9a-f]{64}\\\"")
                || !ObjectId.isId(request.expectedLocalCommit())
                || !ObjectId.isId(request.expectedTrackedCommit())
                || request.orderedCommits().isEmpty() || request.orderedCommits().size() > MAX_REPLAY_COMMITS
                || request.orderedCommits().stream().anyMatch(id -> !ObjectId.isId(id))) {
            throw new IllegalArgumentException("A complete, bounded and reviewed replay is required");
        }
        var reviewed = read(transfer, repositoryId, workspaceId, branch);
        if (!reviewed.refTag().equals(request.expectedRefTag())
                || !Objects.equals(reviewed.localCommit(), request.expectedLocalCommit())
                || !Objects.equals(reviewed.upstream().trackedCommit(), request.expectedTrackedCommit())
                || !reviewed.commits().stream().map(Commit::id).toList().equals(request.orderedCommits())) {
            throw new BranchHeadConflictException(branch, request.expectedLocalCommit(), reviewed.localCommit(),
                    "The reviewed rebase plan changed");
        }
        if (reviewed.status() != State.REPLAY) {
            throw new IllegalArgumentException("Only a divergent, linear and reviewed replay may be applied");
        }
        // A reviewed replay must be based on the current default-branch tip. Do not silently
        // rewrite onto an older fetch when the source has advanced since that fetch.
        if (!Objects.equals(reviewed.upstream().sourceCommit(), reviewed.upstream().trackedCommit())) {
            throw new BranchHeadConflictException(branch, reviewed.upstream().trackedCommit(),
                    reviewed.upstream().sourceCommit(), "Fetch the current upstream before replaying the branch");
        }
        return transfer.target().replayLinearHistory(branch, reviewed.localCommit(), reviewed.mergeBase(),
                reviewed.upstream().trackedCommit(), request.orderedCommits(), request.resolutions(), transfer.actor(), () -> {
                    var current = readRefTag(transfer, repositoryId, workspaceId, branch);
                    if (!reviewed.refTag().equals(current)) {
                        throw new BranchHeadConflictException(branch, reviewed.localCommit(), null,
                                "A source, target or tracking Git ref changed during the reviewed rebase");
                    }
                }, publish);
    }

    private static Refs readRefs(GitUpstreamTransfer transfer, String branch) throws IOException {
        if (branch == null || !Repository.isValidRefName(Constants.R_HEADS + branch)
                || branch.startsWith(DslGitRepository.EXTERNAL_SYNC_BRANCH_PREFIX)) {
            throw new IllegalArgumentException("An exact local branch is required");
        }
        var upstream = transfer.inspect();
        var local = transfer.target().readFreshRef(Constants.R_HEADS + branch);
        String head = local == null || local.isSymbolic() || local.getObjectId() == null
                ? null : local.getObjectId().name();
        return new Refs(head, upstream);
    }

    public String refTag() {
        return refTag(repositoryId, workspaceId, branch, new Refs(localCommit, upstream));
    }

    private static String refTag(String repositoryId, String workspaceId, String branch, Refs refs) {
        var upstream = refs.upstream();
        var identity = new StringBuilder();
        for (String value : new String[]{repositoryId, workspaceId, branch, refs.localCommit(),
                upstream.sourceRepositoryId(), upstream.sourceBranch(), upstream.sourceCommit(),
                upstream.trackingRef(), upstream.trackedCommit()}) {
            if (value == null) identity.append("-:");
            else identity.append(value.length()).append(':').append(value);
        }
        return "W/\"" + StableIdentityHash.sha256(identity.toString()) + "\"";
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
