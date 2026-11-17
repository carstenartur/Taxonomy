package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import static com.taxonomy.workspace.service.UpstreamRebasePlan.State.*;
import static org.assertj.core.api.Assertions.*;

class UpstreamRebasePlanTest {
    private final DslGitRepository store = new DslGitRepository();
    private final GitUpstreamRef tracking = new GitUpstreamRef("origin", "main");

    @AfterEach void close() { store.close(); }

    @Test
    void rechecksAuthorityBeforeReadingGitAndAfterComputingThePlan() throws Exception {
        var events = new ArrayList<String>();
        try (var repository = new DslGitRepository() {
            @Override
            public Ref readFreshRef(String name) throws IOException {
                events.add(name);
                return super.readFreshRef(name);
            }
        }) {
            var transfer = new GitUpstreamTransfer(tracking, repository, repository, null, "owner",
                    () -> events.add("authorize"));
            assertThat(UpstreamRebasePlan.read(transfer, "fork", null, "review").status()).isEqualTo(NO_LOCAL_BRANCH);
            assertThat(events).containsExactly("authorize", "refs/heads/main", tracking.name(), "refs/heads/review", "authorize");
        }
    }

    @Test
    void distinguishesAnAlreadyIntegratedTipFromALocalMergeThatCannotBeReplayedLinearly() throws Exception {
        String base = commit("base"), upstream = commit("upstream", base);
        String one = commit("one", base), two = commit("two", base), merge = commit("merge", one, two);
        var transfer = origin(base, upstream);
        store.createBranchAtCommit("ahead", commit("retains upstream", upstream));
        store.createBranchAtCommit("merged", merge);
        var ahead = UpstreamRebasePlan.read(transfer, "fork", null, "ahead");
        assertThat(ahead.status()).isEqualTo(UP_TO_DATE);
        assertThat(ahead.mergeBase()).isEqualTo(upstream);
        var plan = UpstreamRebasePlan.read(transfer, "fork", null, "merged");
        assertThat(plan.status()).isEqualTo(NON_LINEAR);
        assertThat(plan.commits()).isEmpty();
        assertThat(plan.mergeBase()).isEqualTo(base);
        assertThat(store.getHeadCommit("merged")).isEqualTo(merge);
    }

    @Test
    void ambiguousMergeBasesCannotBeArbitrarilyChosen() throws Exception {
        String base = commit("base"), a = commit("a", base), b = commit("b", base);
        var transfer = origin(base, commit("upstream merge", a, b));
        store.createBranchAtCommit("feature", commit("local merge", b, a));
        var plan = UpstreamRebasePlan.read(transfer, "fork", null, "feature");
        assertThat(plan.status()).isEqualTo(AMBIGUOUS_BASE);
        assertThat(plan.mergeBase()).isNull();
        assertThat(plan.commits()).isEmpty();
    }

    @Test
    void reportsAnOversizedPlanWithoutPresentingAPartialReplay() throws Exception {
        String base = commit("base"), local = base;
        var transfer = origin(base, commit("upstream", base));
        for (int i = 0; i < 201; i++) local = commit("step " + i, local);
        store.createBranchAtCommit("long", local);
        var plan = UpstreamRebasePlan.read(transfer, "fork", null, "long");
        assertThat(plan.status()).isEqualTo(TOO_LONG);
        assertThat(plan.commits()).isEmpty();
        assertThat(plan.localCommit()).isEqualTo(local);
    }

    @Test
    void keepsLongSubjectsBoundedAndRejectsInternalOrInvalidRefNames() throws Exception {
        String base = commit("base");
        var transfer = origin(base, commit("upstream", base));
        String subject = "a".repeat(161), picked = commit(subject, base);
        store.createBranchAtCommit("review", picked);
        var plan = UpstreamRebasePlan.read(transfer, "fork", null, "review");
        assertThat(plan.commits()).containsExactly(new UpstreamRebasePlan.Commit(picked, "a".repeat(160) + "…"));
        for (String invalid : Arrays.asList(null, "../main", DslGitRepository.EXTERNAL_SYNC_BRANCH_PREFIX + "tmp")) {
            assertThatThrownBy(() -> UpstreamRebasePlan.read(transfer, "fork", null, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aCleanLinearReplayPublishesOnceWithSignedPerCommitOrigins() throws Exception {
        // Three independent edits, all of which must survive the real three-way merge.
        String baseDsl = String.join("\n", "north", "two", "three", "four", "five", "six",
                "seven", "eight", "nine", "ten", "eleven", "south", "");
        String upstreamDsl = baseDsl.replace("north\n", "north-upstream\n");
        String localOneDsl = baseDsl.replace("five\n", "five-local\n");
        String localTwoDsl = localOneDsl.replace("ten\n", "ten-local\n");
        String firstReplacementDsl = upstreamDsl.replace("five\n", "five-local\n");
        String finalDsl = firstReplacementDsl.replace("ten\n", "ten-local\n");

        String base = store.commitDsl("seed", baseDsl, "owner", "base");
        store.createBranchAtCommit("main", base);
        String tracked = store.commitDsl("main", upstreamDsl, "owner", "upstream");
        store.createBranchAtCommit("draft", base);
        String one = store.commitDsl("draft", localOneDsl, "owner", "local one");
        String two = store.commitDsl("draft", localTwoDsl, "owner", "local two");
        tracking.advance(store, null, tracked, "owner");
        var transfer = new GitUpstreamTransfer(tracking, store, store, base, "owner", () -> { });
        var plan = UpstreamRebasePlan.read(transfer, "fork", null, "draft");
        assertThat(plan.status()).isEqualTo(REPLAY);
        assertThat(plan.commits().stream().map(UpstreamRebasePlan.Commit::id)).containsExactly(one, two);

        var result = store.replayLinearHistory("draft", two, base, tracked,
                plan.commits().stream().map(UpstreamRebasePlan.Commit::id).toList(), "owner", () -> { });
        assertThat(result.completed()).isTrue();
        assertThat(result.rewritten()).hasSize(2);
        assertThat(result.rewritten().stream().map(DslGitRepository.RebasedCommit::originalCommit))
                .containsExactly(one, two);
        assertThat(store.getHeadCommit("draft")).isEqualTo(result.newHead());
        assertThat(store.getDslAtCommit(result.rewritten().getFirst().replacementCommit()))
                .isEqualTo(firstReplacementDsl);
        assertThat(store.getDslAtCommit(result.newHead())).isEqualTo(finalDsl);
        try (var walk = new org.eclipse.jgit.revwalk.RevWalk(store.getGitRepository())) {
            var tip = walk.parseCommit(ObjectId.fromString(result.newHead()));
            var previous = walk.parseCommit(tip.getParent(0));
            assertThat(previous.name()).isEqualTo(result.rewritten().getFirst().replacementCommit());
            assertThat(walk.parseCommit(previous.getParent(0)).name()).isEqualTo(tracked);
            assertThat(tip.getFullMessage()).contains("Original-Commit: " + two);
            assertThat(previous.getFullMessage()).contains("Original-Commit: " + one);
            assertThat(previous.getTree()).isNotEqualTo(walk.parseCommit(ObjectId.fromString(tracked)).getTree());
            assertThat(tip.getTree()).isNotEqualTo(previous.getTree());
        }
        assertThat(store.verifyCommit(result.newHead())).isNotNull();
    }

    @Test
    void secondReplayedCommitConflictLeavesFirstReplacementUnpublished() throws Exception {
        String baseDsl = String.join("\n", "north", "two", "three", "four", "five", "six",
                "seven", "eight", "nine", "ten", "eleven", "south", "");
        String upstreamDsl = baseDsl.replace("ten\n", "ten-upstream\n");
        String localOneDsl = baseDsl.replace("three\n", "three-local\n");
        String localTwoDsl = localOneDsl.replace("ten\n", "ten-local\n");

        String base = store.commitDsl("seed", baseDsl, "owner", "base");
        store.createBranchAtCommit("main", base);
        String tracked = store.commitDsl("main", upstreamDsl, "owner", "upstream");
        store.createBranchAtCommit("draft", base);
        String first = store.commitDsl("draft", localOneDsl, "owner", "first local");
        String second = store.commitDsl("draft", localTwoDsl, "owner", "conflicting second local");

        var result = store.replayLinearHistory("draft", second, base, tracked,
                java.util.List.of(first, second), "owner", () -> { });
        assertThat(result.completed()).isFalse();
        assertThat(result.conflictCommit()).isEqualTo(second);
        assertThat(result.conflictingPaths()).contains(DslGitRepository.DSL_FILENAME);
        assertThat(result.newHead()).isEqualTo(second);
        assertThat(result.rewritten()).hasSize(1);
        assertThat(result.rewritten().getFirst().originalCommit()).isEqualTo(first);
        assertThat(store.getHeadCommit("draft")).isEqualTo(second);
        assertThat(store.getDslAtCommit(second)).isEqualTo(localTwoDsl);
        assertThat(store.getHeadCommit("main")).isEqualTo(tracked);
        String unpublished = result.rewritten().getFirst().replacementCommit();
        assertThat(store.getDslAtCommit(unpublished))
                .isEqualTo(upstreamDsl.replace("three\n", "three-local\n"));
        try (var walk = new org.eclipse.jgit.revwalk.RevWalk(store.getGitRepository())) {
            assertThat(walk.isMergedInto(walk.parseCommit(ObjectId.fromString(unpublished)),
                    walk.parseCommit(ObjectId.fromString(second)))).isFalse();
            assertThat(walk.parseCommit(ObjectId.fromString(unpublished)).getParent(0).name())
                    .isEqualTo(tracked);
        }
        assertThat(store.getGitRepository().getRefDatabase()
                .getRefsByPrefix(org.eclipse.jgit.lib.Constants.R_HEADS)
                .stream().map(ref -> ref.getObjectId().name())).doesNotContain(unpublished);
    }

    @Test
    void aContentConflictReturnsTheOriginalHeadWithoutAnyBranchPublication() throws Exception {
        String base = store.commitDsl("seed", "first\nsecond\n", "owner", "base");
        store.createBranchAtCommit("main", base);
        String tracked = store.commitDsl("main", "upstream\nsecond\n", "owner", "upstream");
        store.createBranchAtCommit("draft", base);
        String local = store.commitDsl("draft", "local\nsecond\n", "owner", "local");
        var result = store.replayLinearHistory("draft", local, base, tracked, java.util.List.of(local),
                "owner", () -> { });
        assertThat(result.completed()).isFalse();
        assertThat(result.conflictCommit()).isEqualTo(local);
        assertThat(result.conflictingPaths()).contains(DslGitRepository.DSL_FILENAME);
        assertThat(store.getHeadCommit("draft")).isEqualTo(local);
    }

    @Test
    void deniedOrStaleFinalAuthorityNeverPublishesARebasedBranch() throws Exception {
        String base = store.commitDsl("seed", "first\nsecond\n", "owner", "base");
        store.createBranchAtCommit("main", base);
        String tracked = store.commitDsl("main", "first\nsecond\nupstream\n", "owner", "upstream");
        store.createBranchAtCommit("draft", base);
        String local = store.commitDsl("draft", "first\nsecond\n", "owner", "local");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> store.replayLinearHistory("draft", local, base, tracked,
                java.util.List.of(local), "owner", () -> {
                    if (calls.incrementAndGet() == 2) throw new org.springframework.security.access.AccessDeniedException("revoked");
                })).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(store.getHeadCommit("draft")).isEqualTo(local);

        String moved = store.commitDsl("draft", "new version\n", "owner", "concurrent");
        assertThatThrownBy(() -> store.replayLinearHistory("draft", local, base, tracked,
                java.util.List.of(local), "owner", () -> { }))
                .isInstanceOf(BranchHeadConflictException.class);
        assertThat(store.getHeadCommit("draft")).isEqualTo(moved);
    }

    private GitUpstreamTransfer origin(String base, String upstream) throws IOException {
        store.createBranchAtCommit("main", upstream);
        tracking.advance(store, null, upstream, "owner");
        return new GitUpstreamTransfer(tracking, store, store, base, "owner", () -> {});
    }

    private String commit(String subject, String... parents) throws IOException {
        try (var objects = store.getGitRepository().newObjectInserter()) {
            var commit = new CommitBuilder();
            commit.setTreeId(objects.insert(new TreeFormatter()));
            commit.setParentIds(Arrays.stream(parents).map(ObjectId::fromString).toList());
            var author = new PersonIdent("owner", "owner@example.test");
            commit.setAuthor(author);
            commit.setCommitter(author);
            commit.setMessage(subject);
            var id = objects.insert(commit);
            objects.flush();
            return id.name();
        }
    }
}
