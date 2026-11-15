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
