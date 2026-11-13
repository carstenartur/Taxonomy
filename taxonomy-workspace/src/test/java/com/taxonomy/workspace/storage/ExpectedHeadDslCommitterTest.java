package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.storage.ExpectedHeadDslCommitter.CommitRequest;
import com.taxonomy.workspace.signing.SigningTestKeys;
import com.taxonomy.editor.EditorPersistenceFixture;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpectedHeadDslCommitterTest {

    private final ExpectedHeadDslCommitter committer =
            new ExpectedHeadDslCommitter();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedCandidateSurvivesReopeningWithExactBytesAndParents(boolean merge, @TempDir Path temp) throws Exception {
        var signatures = SigningTestKeys.createService(Files.createDirectory(temp.resolve("keys")));
        var directory = temp.resolve("objects.git").toFile();
        String base, incoming, candidate;
        byte[] original;
        try (var raw = FileRepositoryBuilder.create(directory)) {
            raw.create(true);
            var git = new DslGitRepository(raw, false).withSignatureService(signatures, "workspace-fixture");
            base = git.commitDslIfHeadMatches("draft", null, "# base", "alice", "base");
            incoming = merge ? git.commitDslIfHeadMatches("source", null, "# other", "bob", "source") : null;
            candidate = git.prepareDslCommit("sync-42", "draft", base, incoming, "# reviewed", "alice", "reviewed", null);
            assertThat(git.getHeadCommit("draft")).isEqualTo(base);
            assertThat(git.findPreparedDslCommit("sync-42", "draft")).isEqualTo(candidate);
            assertThat(git.prepareDslCommit("sync-42", "draft", base, incoming, "# reviewed", "alice", "reviewed", null))
                    .isEqualTo(candidate);
            assertThatThrownBy(() -> git.prepareDslCommit("sync-42", "draft", base, incoming, "# different", "alice", "reviewed", null))
                    .isInstanceOf(IOException.class);
            assertThat(git.findPreparedDslCommit("sync-42", "sync-base")).isNull();
            assertThat(git.pinExistingDslCommit("sync-42", "sync-base", null, base)).isEqualTo(base);
            assertThat(git.getHeadCommit("sync-base")).isNull();
            assertThatThrownBy(() -> git.releasePreparedDslCommit("sync-42", "draft", base)).isInstanceOf(IOException.class);
            original = raw.open(ObjectId.fromString(candidate)).getBytes();
            assertThat(git.verifyCommit(candidate).status().name()).isEqualTo("VERIFIED");
            try (var walk = new RevWalk(raw)) {
                var commit = walk.parseCommit(ObjectId.fromString(candidate));
                assertThat(commit.getParentCount()).isEqualTo(merge ? 2 : 1);
                assertThat(commit.getParent(0).name()).isEqualTo(base);
                if (merge) assertThat(commit.getParent(1).name()).isEqualTo(incoming);
            }
        }
        try (var raw = new FileRepositoryBuilder().setGitDir(directory).build()) {
            var git = new DslGitRepository(raw, false).withSignatureService(signatures, "workspace-fixture");
            assertThat(git.findPreparedDslCommit("sync-42", "draft")).isEqualTo(candidate);
            assertThatThrownBy(() -> git.publishPreparedDslCommit("sync-42", "draft", null, candidate)).isInstanceOf(IOException.class);
            assertThat(git.publishPreparedDslCommit("sync-42", "draft", base, candidate)).isEqualTo(candidate);
            assertThat(git.publishPreparedDslCommit("sync-42", "draft", base, candidate)).isEqualTo(candidate);
            assertThat(raw.open(ObjectId.fromString(candidate)).getBytes()).containsExactly(original);
            assertThat(git.publishPreparedDslCommit("sync-42", "sync-base", null, base)).isEqualTo(base);
            git.releasePreparedDslCommit("sync-42", "draft", candidate);
            git.releasePreparedDslCommit("sync-42", "draft", candidate);
            assertThat(git.findPreparedDslCommit("sync-42", "draft")).isNull();
            assertThat(git.findPreparedDslCommit("sync-42", "sync-base")).isEqualTo(base);
        }
    }

    @Test
    void hibernatePinRetainsTheExactSignedCandidateAcrossDatabaseFactoryRestarts(@TempDir Path temp) throws Exception {
        var signatures = SigningTestKeys.createService(Files.createDirectory(temp.resolve("keys")));
        String url = "jdbc:hsqldb:file:" + temp.resolve("journal") + ";shutdown=true";
        String base, candidate;
        byte[] bytes;
        try (var db = new EditorPersistenceFixture(url);
             var repositories = new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(db.factory), null, signatures)) {
            var git = repositories.openWorkspaceRepository("prepared-storage");
            base = git.commitDslIfHeadMatches("draft", null, "# base", "alice", "base");
            candidate = git.prepareDslCommit("operation", "draft", base, null, "# update", "alice", "update", null);
            bytes = git.getGitRepository().open(ObjectId.fromString(candidate)).getBytes();
            assertThat(git.getHeadCommit("draft")).isEqualTo(base);
        }
        try (var db = new EditorPersistenceFixture(url);
             var repositories = new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(db.factory), null, signatures)) {
            var git = repositories.openWorkspaceRepository("prepared-storage");
            assertThat(git.findPreparedDslCommit("operation", "draft")).isEqualTo(candidate);
            assertThat(git.publishPreparedDslCommit("operation", "draft", base, candidate)).isEqualTo(candidate);
            var movement=git.preparedPublicationTime("operation","draft",base,candidate);
            assertThat(movement).isNotNull();
            assertThat(git.preparedPublicationTime("another-operation","draft",base,candidate)).isNull();
            assertThat(git.preparedPublicationTime("operation","draft",null,candidate)).isNull();
            assertThat(git.getGitRepository().open(ObjectId.fromString(candidate)).getBytes()).containsExactly(bytes);
        }
        try (var db = new EditorPersistenceFixture(url);
             var repositories = new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(db.factory), null, signatures)) {
            var git = repositories.openWorkspaceRepository("prepared-storage");
            assertThat(git.publishPreparedDslCommit("operation", "draft", base, candidate)).isEqualTo(candidate);
            assertThat(git.verifyCommit(candidate).status().name()).isEqualTo("VERIFIED");
            assertThat(git.preparedPublicationTime("operation","draft",base,candidate)).isNotNull();
            git.releasePreparedDslCommit("operation", "draft", candidate);
            assertThat(git.findPreparedDslCommit("operation", "draft")).isNull();
            assertThat(git.getHeadCommit("draft")).isEqualTo(candidate);
        }
    }

    @Test
    void terminalCleanupUsesExistingStorageAndChecksTheGrantAtRefDeletion(@TempDir Path temp) throws Exception {
        var signatures = SigningTestKeys.createService(Files.createDirectory(temp.resolve("keys")));
        String url = "jdbc:hsqldb:file:" + temp.resolve("cleanup") + ";shutdown=true";
        String current, prepared, neighbor;
        try (var db = new EditorPersistenceFixture(url)) {
            var git = db.repositories.openWorkspaceRepository("reviewed");
            current = git.commitDslIfHeadMatches("draft", null, "# base", "alice", "original");
            prepared = git.prepareDslCommit("first", "draft", current, null, "# retained", "alice", "first", null);
            neighbor = git.prepareDslCommit("second", "draft", current, null, "# neighbor", "alice", "second", null);
        }
        try (var db = new EditorPersistenceFixture(url);
             var reopened = new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(db.factory), null, signatures, db.factory)) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            assertThatThrownBy(() -> reopened.releasePublicationPin("source", "reviewed", "first", "draft", prepared, () -> {
                if (calls.incrementAndGet() >= 3) throw new org.springframework.security.access.AccessDeniedException("grant withdrawn");
            })).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            var observed = db.repositories.openWorkspaceRepository("reviewed");
            assertThat(observed.findPreparedDslCommit("first", "draft")).isEqualTo(prepared);
            assertThatThrownBy(() -> reopened.releasePublicationPin("source", "reviewed", "first", "draft", neighbor, () -> {}))
                    .isInstanceOf(IOException.class);
            reopened.releasePublicationPin("source", "reviewed", "first", "draft", prepared, () -> {});
            reopened.releasePublicationPin("source", "reviewed", "first", "draft", prepared, () -> {});
            assertThat(observed.findPreparedDslCommit("first", "draft")).isNull();
            assertThat(observed.findPreparedDslCommit("second", "draft")).isEqualTo(neighbor);
            assertThat(observed.getHeadCommit("draft")).isEqualTo(current);
            assertThatThrownBy(() -> reopened.requireRemovedWorkspaceStorage("reviewed",() -> {})).isInstanceOf(IOException.class);
            assertThat(observed.findPreparedDslCommit("second","draft")).isEqualTo(neighbor);
            int count = storedRepositoryCount(db);
            reopened.requireRemovedWorkspaceStorage("missing",() -> {});
            assertThatThrownBy(() -> reopened.releasePublicationPin("source", "missing", "first", "draft", prepared, () -> {}))
                    .isInstanceOf(IOException.class);
            assertThat(storedRepositoryCount(db)).isEqualTo(count);
        }
    }

    private static int storedRepositoryCount(EditorPersistenceFixture db) {
        try (var session = db.factory.openSession()) {
            return ((Number) session.createNativeQuery("select count(*) from git_repository_lifecycle", Long.class).getSingleResult()).intValue();
        }
    }

    @Test
    void previouslyOpenedHibernateHandlesObserveEachOthersPinsAndConditionalRelease(@TempDir Path temp) throws Exception {
        String url = "jdbc:hsqldb:file:" + temp.resolve("independent") + ";shutdown=true";
        try (var first = new EditorPersistenceFixture(url); var second = new EditorPersistenceFixture(url)) {
            var writer = first.repositories.openWorkspaceRepository("shared-storage");
            var observer = second.repositories.openWorkspaceRepository("shared-storage");
            String base = writer.commitDslIfHeadMatches("draft", null, "# base", "alice", "base");
            assertThat(observer.findPreparedDslCommit("sync", "draft")).isNull();
            String candidate = writer.prepareDslCommit("sync", "draft", base, null, "# edit", "alice", "edit", null);
            assertThat(observer.findPreparedDslCommit("sync", "draft")).isEqualTo(candidate);
            assertThat(observer.publishPreparedDslCommit("sync", "draft", base, candidate)).isEqualTo(candidate);
            assertThat(writer.publishPreparedDslCommit("sync", "draft", base, candidate)).isEqualTo(candidate);
            writer.releasePreparedDslCommit("sync", "draft", candidate);
            assertThat(observer.findPreparedDslCommit("sync", "draft")).isNull();
        }
    }

    @Test
    void independentRepositoriesCannotReplaceAWinningOperationPin(@TempDir Path temp) throws Exception {
        var directory = temp.resolve("shared.git").toFile();
        try (var first = FileRepositoryBuilder.create(directory);
             var workers = Executors.newFixedThreadPool(2)) {
            first.create(true);
            var owner = new DslGitRepository(first, false);
            var base = owner.commitDslIfHeadMatches("draft", null, "# base", "alice", "base");
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var results = List.of("# left", "# right").stream().map(dsl -> workers.submit(() -> {
                try (var raw = new FileRepositoryBuilder().setGitDir(directory).build()) {
                    var worker = new DslGitRepository(raw, false);
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Other writer did not start");
                    try {
                        return worker.prepareDslCommit("competing", "draft", base, null, dsl, "alice", "operation", null);
                    } catch (IOException lostRace) {
                        return null;
                    }
                }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstResult = results.get(0).get(15, TimeUnit.SECONDS);
            var secondResult = results.get(1).get(15, TimeUnit.SECONDS);
            var pin = owner.findPreparedDslCommit("competing", "draft");
            assertThat(pin).isNotNull();
            assertThat(java.util.Arrays.asList(firstResult, secondResult)).containsExactlyInAnyOrder(pin, null);
            assertThat(owner.getHeadCommit("draft")).isEqualTo(base);
            assertThat(owner.publishPreparedDslCommit("competing", "draft", base, pin)).isEqualTo(pin);
        }
    }

    @Test
    void aPreparedInitialCommitNeverOverwritesAnIndependentRootOrTheWrongBranch() throws Exception {
        try (var git = new DslGitRepository()) {
            var candidate = git.prepareDslCommit("initial", "draft", null, null, "# reviewed", "alice", "reviewed", null);
            var independent = git.commitDslIfHeadMatches("draft", null, "# independent", "bob", "independent");
            assertThatThrownBy(() -> git.publishPreparedDslCommit("initial", "other", null, candidate))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> git.publishPreparedDslCommit("initial", "draft", null, candidate))
                    .isInstanceOf(BranchHeadConflictException.class);
            assertThat(git.getHeadCommit("other")).isNull();
            assertThat(git.getHeadCommit("draft")).isEqualTo(independent);
            assertThat(git.findPreparedDslCommit("initial", "draft")).isEqualTo(candidate);
            assertThat(git.prepareDslCommit("initial", "draft", null, null, "# reviewed", "alice", "reviewed", null))
                    .isEqualTo(candidate);
            assertThatThrownBy(() -> git.prepareDslCommit("initial", "draft", null, null, "# reviewed", "mallory", "reviewed", null))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void pinsExistingFastForwardsAndNoOpsWithoutAdoptingAnIndependentHead() throws Exception {
        try (var git = new DslGitRepository()) {
            String base = git.commitDslIfHeadMatches("draft", null, "# base", "alice", "base");
            git.createBranchAtCommit("source", base);
            String child = git.commitDslIfHeadMatches("source", base, "# child", "bob", "child");
            String other = git.commitDslIfHeadMatches("unrelated", null, "# unrelated", "bob", "unrelated");
            assertThatThrownBy(() -> git.pinExistingDslCommit("bad-ff", "draft", base, other)).isInstanceOf(IOException.class);
            assertThat(git.findPreparedDslCommit("bad-ff", "draft")).isNull();
            assertThat(git.pinExistingDslCommit("no-op", "draft", base, base)).isEqualTo(base);
            assertThat(git.publishPreparedDslCommit("no-op", "draft", base, base)).isEqualTo(base);
            assertThat(git.pinExistingDslCommit("ff", "draft", base, child)).isEqualTo(child);
            assertThatThrownBy(() -> git.pinExistingDslCommit("ff", "draft", base, base)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> git.publishPreparedDslCommit("ff", "draft", base, other)).isInstanceOf(IOException.class);
            assertThat(git.publishPreparedDslCommit("ff", "draft", base, child)).isEqualTo(child);
            assertThat(git.publishPreparedDslCommit("ff", "draft", base, child)).isEqualTo(child);
            String moved = git.commitDslIfHeadMatches("draft", child, "# independent", "bob", "independent");
            assertThatThrownBy(() -> git.publishPreparedDslCommit("ff", "draft", base, child))
                    .isInstanceOf(BranchHeadConflictException.class);
            assertThat(git.getHeadCommit("draft")).isEqualTo(moved);
            assertThat(git.findPreparedDslCommit("ff", "draft")).isEqualTo(child);
        }
    }

    @Test
    void createsInitialAndChildCommitsOnlyAtTheExpectedHead() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            var initial = committer.commit(repository, new CommitRequest(
                    "draft",
                    null,
                    "meta {\n  language: \"taxdsl\";\n}\n",
                    "alice",
                    "Initialize architecture"));

            assertThat(initial.previousHeadCommit()).isNull();
            assertThat(initial.refUpdateResult()).isEqualTo(RefUpdate.Result.NEW);
            assertThat(repository.getHeadCommit("draft"))
                    .isEqualTo(initial.commitId());

            var child = committer.commit(repository, new CommitRequest(
                    "draft",
                    initial.commitId(),
                    "element APP-1 type Application {\n}\n",
                    "alice",
                    "Add APP-1"));

            assertThat(child.previousHeadCommit())
                    .isEqualTo(initial.commitId());
            assertThat(child.refUpdateResult())
                    .isEqualTo(RefUpdate.Result.FAST_FORWARD);
            assertThat(repository.getHeadCommit("draft"))
                    .isEqualTo(child.commitId());
            assertThat(repository.getDslAtHead("draft"))
                    .contains("element APP-1 type Application");
            assertThat(repository.getCommitCount("draft")).isEqualTo(2);
            assertThat(repository.getHeadCommitInfo("draft").author())
                    .isEqualTo("alice");
        }
    }

    @Test
    void rejectsAStaleExpectedHeadWithoutChangingTheBranch() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            String initial = committer.commit(repository, new CommitRequest(
                    "draft", null, "meta {\n}\n", "alice", "Initial"))
                    .commitId();
            String competing = repository.commitDsl(
                    "draft",
                    "element OTHER-1 type Application {\n}\n",
                    "bob",
                    "Concurrent edit");

            assertThatThrownBy(() -> committer.commit(repository, new CommitRequest(
                    "draft",
                    initial,
                    "element APP-1 type Application {\n}\n",
                    "alice",
                    "Stale command")))
                    .isInstanceOfSatisfying(
                            BranchHeadConflictException.class,
                            conflict -> {
                                assertThat(conflict.getBranch()).isEqualTo("draft");
                                assertThat(conflict.getExpectedHeadCommit())
                                        .isEqualTo(initial);
                                assertThat(conflict.getActualHeadCommit())
                                        .isEqualTo(competing);
                            });

            assertThat(repository.getHeadCommit("draft")).isEqualTo(competing);
            assertThat(repository.getDslAtHead("draft"))
                    .contains("OTHER-1")
                    .doesNotContain("APP-1");
            assertThat(repository.getCommitCount("draft")).isEqualTo(2);
        }
    }

    @Test
    void distinguishesAbsentBranchFromAnExistingBranch() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            String nonexistentExpected = ObjectId.fromString(
                    "1111111111111111111111111111111111111111").name();

            assertThatThrownBy(() -> committer.commit(repository, new CommitRequest(
                    "draft",
                    nonexistentExpected,
                    "meta {\n}\n",
                    "alice",
                    "Unexpected parent")))
                    .isInstanceOfSatisfying(
                            BranchHeadConflictException.class,
                            conflict -> {
                                assertThat(conflict.getExpectedHeadCommit())
                                        .isEqualTo(nonexistentExpected);
                                assertThat(conflict.getActualHeadCommit()).isNull();
                            });

            String initial = committer.commit(repository, new CommitRequest(
                    "draft", null, "meta {\n}\n", "alice", "Initial"))
                    .commitId();
            assertThatThrownBy(() -> committer.commit(repository, new CommitRequest(
                    "draft",
                    null,
                    "element APP-1 type Application {\n}\n",
                    "alice",
                    "Unexpected initial command")))
                    .isInstanceOfSatisfying(
                            BranchHeadConflictException.class,
                            conflict -> {
                                assertThat(conflict.getExpectedHeadCommit()).isNull();
                                assertThat(conflict.getActualHeadCommit())
                                        .isEqualTo(initial);
                            });
        }
    }

    @Test
    void verifiesExistingHeadThroughNoChangeRefUpdate() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            String head = committer.commit(repository, new CommitRequest(
                    "draft", null, "meta {\n}\n", "alice", "Initial"))
                    .commitId();

            assertThat(committer.verifyExpectedHead(repository, "draft", head))
                    .isEqualTo(head);
            assertThat(repository.getHeadCommit("draft")).isEqualTo(head);
            assertThat(repository.getCommitCount("draft")).isEqualTo(1);
        }
    }

    @Test
    void validatesBranchExpectedCommitAndRequiredContent() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitRequest(
                        " ", null, "dsl", "alice", "message"))
                .withMessageContaining("branch must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitRequest(
                        "bad branch", null, "dsl", "alice", "message"))
                .withMessageContaining("invalid branch name");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitRequest(
                        "draft", " ", "dsl", "alice", "message"))
                .withMessageContaining("must be null or a full commit ID");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitRequest(
                        "draft", "not-a-commit", "dsl", "alice", "message"));
        assertThatThrownBy(() -> new CommitRequest(
                "draft", null, null, "alice", "message"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("dslText");
    }

    @Test
    void normalizesDefaultAuthorAndMessage() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            var result = committer.commit(repository, new CommitRequest(
                    " draft ", null, "meta {\n}\n", " ", " "));

            assertThat(repository.getHeadCommit("draft"))
                    .isEqualTo(result.commitId());
            assertThat(repository.getHeadCommitInfo("draft").author())
                    .isEqualTo("taxonomy");
            assertThat(repository.getHeadCommitInfo("draft").message())
                    .isEqualTo("DSL update");
        }
    }
}
