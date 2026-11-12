package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.signing.SignatureStatus;
import com.taxonomy.workspace.signing.SigningTestKeys;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class GitHistoryTransferTest {
    @TempDir Path keys;

    @Test
    void aRealClosureFitsItsExactLimitsAndCannotPublishWhenOneObjectOrByteIsMissing() throws Exception {
        try (var source = new DslGitRepository()) {
            source.commitDsl("main", "first version", "alice", "First");
            String tip = source.commitDsl("main", "updated version", "alice", "Second");
            var sizes = new ArrayList<Long>();
            try (var walk = new ObjectWalk(source.getGitRepository()); var reader = source.getGitRepository().newObjectReader()) {
                walk.markStart(walk.parseCommit(ObjectId.fromString(tip)));
                RevObject object;
                while ((object = walk.next()) != null) sizes.add(reader.getObjectSize(object, ObjectReader.OBJ_ANY));
                while ((object = walk.nextObject()) != null) sizes.add(reader.getObjectSize(object, ObjectReader.OBJ_ANY));
            }
            long bytes = sizes.stream().mapToLong(Long::longValue).sum();
            long largest = sizes.stream().mapToLong(Long::longValue).max().orElseThrow();
            var upstream = new GitUpstreamRef("source", "main");
            record Rejected(GitHistoryTransfer.Limits limits, String reason) {}
            for (var rejected : List.of(
                    new Rejected(new GitHistoryTransfer.Limits(sizes.size() - 1, largest, bytes), "object budget"),
                    new Rejected(new GitHistoryTransfer.Limits(sizes.size(), largest - 1, bytes), "byte budget"),
                    new Rejected(new GitHistoryTransfer.Limits(sizes.size(), largest, bytes - 1), "byte budget"))) {
                try (var target = new DslGitRepository()) {
                    String local = target.commitDsl("draft", "private work", "bob", "Keep this");
                    var bounded = new GitHistoryTransfer(rejected.limits());
                    assertThatThrownBy(() -> bounded.transfer(source, target, tip, List.of()))
                            .isInstanceOf(IOException.class).hasMessageContaining(rejected.reason());
                    assertThat(upstream.head(target)).isNull();
                    assertThat(target.getHeadCommit("draft")).isEqualTo(local);
                    GitHistoryTransfer.copyCommitClosure(source, target, tip, List.of());
                    upstream.advance(target, null, tip, "bob");
                    assertThat(upstream.head(target)).isEqualTo(tip);
                    assertThat(target.getHeadCommit("draft")).isEqualTo(local);
                }
            }
            try (var target = new DslGitRepository()) {
                var bounded = new GitHistoryTransfer(new GitHistoryTransfer.Limits(sizes.size(), largest, bytes));
                assertThat(bounded.transfer(source, target, tip, List.of())).isEqualTo(bytes);
                assertThat(upstream.head(target)).isNull();
                upstream.advance(target, null, tip, "bob");
                assertThat(target.getDslAtCommit(tip)).isEqualTo("updated version");
            }
        }
    }

    @Test
    void aRestrictedTransferCannotOverrideTheProductionBudgets() {
        assertThatIllegalArgumentException().isThrownBy(() -> new GitHistoryTransfer.Limits(1_000_001, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GitHistoryTransfer.Limits(1, 64L * 1024 * 1024 + 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GitHistoryTransfer.Limits(1, 1, 1024L * 1024 * 1024 + 1));
    }

    @Test
    void thePublicTransferRejectsAnActualObjectOneByteAboveTheProductionLimit() throws Exception {
        Path content = keys.resolve("oversized.dsl");
        long size = GitHistoryTransfer.MAX_OBJECT_BYTES + 1L;
        try (var file = new RandomAccessFile(content.toFile(), "rw")) {
            file.setLength(size);
        }
        try (var git = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(keys.resolve("source.git").toFile()).call();
             var source = new DslGitRepository(git.getRepository(), false); var target = new DslGitRepository()) {
            String tip;
            try (var writer = git.getRepository().newObjectInserter(); var input = Files.newInputStream(content)) {
                ObjectId blob = writer.insert(Constants.OBJ_BLOB, size, input);
                var tree = new TreeFormatter();
                tree.append("large.dsl", FileMode.REGULAR_FILE, blob);
                var commit = new CommitBuilder();
                commit.setTreeId(writer.insert(tree));
                commit.setAuthor(new PersonIdent("tester", "tester@example.test"));
                commit.setCommitter(commit.getAuthor());
                commit.setMessage("One byte over limit");
                tip = writer.insert(commit).name();
                writer.flush();
                assertThat(git.getRepository().open(blob).getSize()).isEqualTo(size);
            }
            String independent = target.commitDsl("draft", "private", "bob", "Keep");
            assertThatThrownBy(() -> GitHistoryTransfer.copyCommitClosure(source, target, tip, List.of()))
                    .isInstanceOf(IOException.class).hasMessageContaining("byte budget");
            assertThat(new GitUpstreamRef("source", "main").head(target)).isNull();
            assertThat(target.getHeadCommit("draft")).isEqualTo(independent);
        }
    }

    @Test void incrementalFetchAdvancesOnlyTheSelectedUpstreamAndRejectsStaleOrRewoundHeads() throws Exception {
        try (var upstream = new DslGitRepository(); var target = new DslGitRepository()) {
            String base = upstream.commitDsl("main", "initial", "alice", "Initial");
            String local = target.commitDsl("draft", "personal edits", "bob", "Local only");
            var tracked = new GitUpstreamRef("source-a", "main");
            var other = new GitUpstreamRef("source-b", "main");
            assertThat(tracked.head(target)).isNull();
            long firstBytes = GitHistoryTransfer.copyCommitClosure(upstream, target, base, List.of());
            tracked.advance(target, null, base, "alice");
            assertThat(firstBytes).isPositive();
            String next = upstream.commitDsl("main", "new", "alice", "New source");
            long deltaBytes = GitHistoryTransfer.copyCommitClosure(upstream, target, next, List.of(base));
            assertThat(deltaBytes).isPositive();
            tracked.advance(target, base, next, "alice");
            assertThat(tracked.head(target)).isEqualTo(next);
            assertThat(GitHistoryTransfer.copyCommitClosure(upstream, target, next, List.of(next))).isZero();
            tracked.advance(target, next, next, "alice");
            assertThatThrownBy(() -> tracked.advance(target, base, base, "alice")).isInstanceOf(BranchHeadConflictException.class);
            assertThatThrownBy(() -> tracked.advance(target, next, base, "alice")).isInstanceOf(BranchHeadConflictException.class);
            assertThat(other.head(target)).isNull();
            assertThat(tracked.head(target)).isEqualTo(next);
            assertThat(target.getHeadCommit("draft")).isEqualTo(local);
            assertThat(target.listBranches()).extracting(DslBranch::name).containsExactly("draft");
        }
    }

    @Test void independentHibernateHandlesCompeteForOneExpectedTrackingRef() throws Exception {
        String database = "jdbc:hsqldb:mem:tracking-cas-" + java.util.UUID.randomUUID();
        try (var persistence = new com.taxonomy.editor.EditorPersistenceFixture(database);
             var secondFactory = new DslGitRepositoryFactory(
                     new io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory(persistence.factory));
             var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = persistence.repositories.openWorkspaceRepository("target");
            var b = secondFactory.openWorkspaceRepository("target");
            String base = a.commitDsl("main", "common", "owner", "Common");
            a.createBranchAtCommit("topic", base);
            String left = a.commitDsl("main", "left", "owner", "Left");
            String right = a.commitDsl("topic", "right", "owner", "Right");
            var ref = new GitUpstreamRef("origin", "main");
            ref.advance(a, null, base, "owner");
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            var updates = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (var attempt : java.util.Map.of(a, left, b, right).entrySet()) {
                updates.add(pool.submit(() -> {
                    barrier.await(15, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        ref.advance(attempt.getKey(), base, attempt.getValue(), "owner");
                        return true;
                    } catch (BranchHeadConflictException changed) {
                        return false;
                    }
                }));
            }
            assertThat(List.of(updates.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS),
                    updates.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(ref.head(a)).isIn(left, right);
            assertThat(ref.head(b)).isEqualTo(ref.head(a));
            assertThat(a.getHeadCommit("main")).isEqualTo(left);
            assertThat(b.getHeadCommit("topic")).isEqualTo(right);
        }
    }

    @Test void transferredHistorySurvivesClosingAndReopeningHibernateStorage() throws Exception {
        String database = "jdbc:hsqldb:mem:history-transfer-" + java.util.UUID.randomUUID();
        String first, published;
        try (var persistence = new com.taxonomy.editor.EditorPersistenceFixture(database)) {
            var source = persistence.repositories.openWorkspaceRepository("author");
            var target = persistence.repositories.getCentralRepository("architecture");
            first = source.commitDsl("main", "# requirement 9", "architect", "Initial requirement contribution");
            String tenth = source.commitDsl("main", "# requirement 10", "architect", "Next requirement contribution");
            String accepted = target.commitDsl("main", "# central", "owner", "Independent architecture");
            GitHistoryTransfer.copyCommitClosure(source, target, tenth, List.of(accepted));
            published = target.commitMergeDslIfHeadMatches("main", accepted, tenth, "# merged", "architect", "Publish both contributions", null);
        }
        try (var reopened = new com.taxonomy.editor.EditorPersistenceFixture(database)) {
            var target = reopened.repositories.getCentralRepository("architecture");
            assertThat(target.getHeadCommit("main")).isEqualTo(published);
            assertThat(target.isAncestor(first, published)).isTrue();
            assertThat(target.getDslAtCommit(first)).isEqualTo("# requirement 9");
            assertThat(target.getDslHistory("main")).extracting(DslCommit::message).contains("Initial requirement contribution");
        }
    }

    @Test void exactOriginalCommitsAndTheirSignaturesRemainParentsOfThePublication() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        try (var source = new DslGitRepository().withSignatureService(signatures, "workspace");
             var target = new DslGitRepository().withSignatureService(signatures, "central")) {
            String first = source.commitDsl("draft", "# requirement 9", "alice", "Review ninth requirement");
            String tenth = source.commitDsl("draft", "# requirement 10", "alice", "Implement tenth requirement");
            String central = target.commitDsl("main", "# central", "bob", "Central architecture");
            byte[] original = source.getGitRepository().open(ObjectId.fromString(first)).getBytes();
            GitHistoryTransfer.copyCommitClosure(source, target, tenth, List.of(central));
            assertThat(target.getHeadCommit("main")).isEqualTo(central);
            assertThat(target.getGitRepository().open(ObjectId.fromString(first)).getBytes()).isEqualTo(original);
            String published = target.commitMergeDslIfHeadMatches("main", central, tenth, "# merged", "alice", "Publish contributions", null);
            try (var walk = new RevWalk(target.getGitRepository())) {
                var commit = walk.parseCommit(ObjectId.fromString(published));
                assertThat(commit.getParents()).extracting(ObjectId::name).containsExactly(central, tenth);
                assertThat(walk.isMergedInto(walk.parseCommit(ObjectId.fromString(first)), commit)).isTrue();
            }
            assertThat(target.verifyCommit(first).status()).isEqualTo(SignatureStatus.VERIFIED);
            assertThat(target.verifyCommit(published).status()).isEqualTo(SignatureStatus.VERIFIED);
            assertThat(target.getDslAtCommit(first)).isEqualTo("# requirement 9");
            assertThatThrownBy(() -> target.commitMergeDslIfHeadMatches("main", central, tenth, "# stale", "alice", "Stale", null))
                    .isInstanceOf(BranchHeadConflictException.class);
            assertThat(target.getHeadCommit("main")).isEqualTo(published);
            GitHistoryTransfer.copyCommitClosure(target, source, published, List.of(tenth));
            assertThat(source.fastForwardIfHeadMatches("draft", tenth, published, "alice", "Adopt published architecture"))
                    .isEqualTo(published);
            assertThat(source.fastForwardIfHeadMatches("draft", published, published, "alice", "Already current"))
                    .isEqualTo(published);
        }
    }

    @Test void aSignedTipCannotSmuggleAnInvalidIntermediateCommitIntoAcceptedAncestry() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        try (var source = new DslGitRepository().withSignatureService(signatures, "workspace");
             var target = new DslGitRepository().withSignatureService(signatures, "central")) {
            String accepted = target.commitDsl("main", "# accepted", "bob", "Accepted");
            String first = source.commitDsl("draft", "# first", "alice", "First");
            String malformed;
            try (var walk = new RevWalk(source.getGitRepository()); var inserter = source.getGitRepository().newObjectInserter()) {
                var parent = walk.parseCommit(ObjectId.fromString(first));
                var commit = new CommitBuilder(); commit.setTreeId(parent.getTree()); commit.setParentId(parent);
                commit.setAuthor(parent.getAuthorIdent()); commit.setCommitter(parent.getCommitterIdent()); commit.setMessage("Invalid intermediate");
                commit.setGpgSignature(new GpgSignature("invalid".getBytes(StandardCharsets.US_ASCII)));
                malformed = inserter.insert(commit).name(); inserter.flush();
                var ref = source.getGitRepository().updateRef("refs/heads/draft");
                ref.setExpectedOldObjectId(ObjectId.fromString(first)); ref.setNewObjectId(ObjectId.fromString(malformed));
                assertThat(ref.update()).isEqualTo(RefUpdate.Result.FAST_FORWARD);
            }
            String trustedTip = source.commitDsl("draft", "# trusted tip", "alice", "Trusted tip");
            assertThat(source.verifyCommit(trustedTip).status()).isEqualTo(SignatureStatus.VERIFIED);
            assertThatThrownBy(() -> GitHistoryTransfer.copyCommitClosure(source, target, trustedTip, List.of(accepted)))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(target.getHeadCommit("main")).isEqualTo(accepted);
            assertThat(target.listBranches()).extracting(DslBranch::name).containsExactly("main");
        }
    }
}
