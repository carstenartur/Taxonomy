package com.taxonomy.dsl.storage;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceDslVersionPort;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DslWorkspaceVersionAdapterTest {

    private static final RepositoryContext WORKSPACE =
            RepositoryContext.workspace("repo-a", "workspace-a", "review", "alice");

    @Test
    void bindsRepositoryBranchActorAndExpectedVersionWithoutASecondResolution() throws Exception {
        try (var factory = new CountingFactory()) {
            var repository = factory.resolveRepository(WORKSPACE);
            String head = repository.commitDsl("review", "# original", "seed", "Seed");
            String draft = repository.commitDsl("draft", "# other branch", "seed", "Draft");
            var other = factory.openWorkspaceRepository("workspace-b");
            String otherHead = other.commitDsl("review", "# other workspace", "seed", "Other");
            factory.resolutions = 0;

            var version = new DslWorkspaceVersionAdapter(factory).openVersion(WORKSPACE, "  " + head + "  ");
            assertThat(version.expectedHeadCommit()).isEqualTo(head);
            assertThat(version.readDsl()).isEqualTo("# original");
            assertThat(version.verifyExpectedHead()).isEqualTo(head);
            assertThat(repository.getCommitCount("review")).isEqualTo(1);
            var result = version.commit("# changed", "Reviewed change");

            assertThat(factory.resolutions).isEqualTo(1);
            assertThat(result.previousHeadCommit()).isEqualTo(head);
            assertThat(result.commitId()).isEqualTo(repository.getHeadCommit("review"));
            assertThat(version.readDsl()).isEqualTo("# original");
            assertThat(repository.getDslAtHead("review")).isEqualTo("# changed");
            assertThat(repository.getHeadCommit("draft")).isEqualTo(draft);
            assertThat(other.getHeadCommit("review")).isEqualTo(otherHead);
            try (var walk = new RevWalk(repository.getGitRepository())) {
                var commit = walk.parseCommit(ObjectId.fromString(result.commitId()));
                assertThat(commit.getParentCount()).isEqualTo(1);
                assertThat(commit.getParent(0).name()).isEqualTo(head);
                assertThat(commit.getAuthorIdent().getName()).isEqualTo("alice");
                assertThat(commit.getFullMessage()).isEqualTo("Reviewed change");
            }
        }
    }

    @Test
    void staleWriteAndNoOpPreserveTheExistingConflictContract() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var repository = factory.resolveRepository(WORKSPACE);
            String expected = repository.commitDsl("review", "# before", "seed", "Before");
            var version = new DslWorkspaceVersionAdapter(factory).openVersion(WORKSPACE, expected);
            String competing = repository.commitDsl("review", "# concurrent", "bob", "Concurrent");

            assertThat(version.readDsl()).isEqualTo("# before");
            assertThatThrownBy(() -> version.commit("# stale", "Stale write"))
                    .isInstanceOfSatisfying(BranchHeadConflictException.class, conflict -> {
                        assertThat(conflict.getBranch()).isEqualTo("review");
                        assertThat(conflict.getExpectedHeadCommit()).isEqualTo(expected);
                        assertThat(conflict.getActualHeadCommit()).isEqualTo(competing);
                    });
            assertThatThrownBy(version::verifyExpectedHead)
                    .isInstanceOfSatisfying(BranchHeadConflictException.class, conflict -> {
                        assertThat(conflict.getExpectedHeadCommit()).isEqualTo(expected);
                        assertThat(conflict.getActualHeadCommit()).isEqualTo(competing);
                    });
            assertThat(repository.getHeadCommit("review")).isEqualTo(competing);
            assertThat(repository.getCommitCount("review")).isEqualTo(2);
        }
    }

    @Test
    void absentVersionCanCreateAnInitialCommitWithoutAnImplicitParent() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var context = RepositoryContext.centralWrite("selected", "accepted", "maintainer");
            var version = new DslWorkspaceVersionAdapter(factory).openVersion(context, null);
            assertThat(version.expectedHeadCommit()).isNull();
            assertThat(version.readDsl()).isEmpty();
            assertThat(version.verifyExpectedHead()).isNull();

            var result = version.commit("# initial", "Initial version");
            var repository = factory.resolveRepository(context);
            assertThat(result.previousHeadCommit()).isNull();
            assertThat(result.commitId()).isEqualTo(repository.getHeadCommit("accepted"));
            try (var walk = new RevWalk(repository.getGitRepository())) {
                assertThat(walk.parseCommit(ObjectId.fromString(result.commitId())).getParentCount()).isZero();
            }
        }
    }

    @Test
    void explicitCentralAndForkVersionsNeverFallBackToTheSystemRepository() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var system = factory.getSystemRepository();
            String systemHead = system.commitDsl("accepted", "# system", "seed", "System");
            var central = RepositoryContext.centralWrite("selected", "accepted", "maintainer");
            var fork = new RepositoryContext("fork-a", null, "accepted", "alice", RepositoryScope.FORK);
            var port = new DslWorkspaceVersionAdapter(factory);

            var centralCommit = port.openVersion(central, null).commit("# central", "Central");
            var forkCommit = port.openVersion(fork, null).commit("# fork", "Fork");

            assertThat(factory.resolveRepository(central).getHeadCommit("accepted"))
                    .isEqualTo(centralCommit.commitId());
            assertThat(factory.resolveRepository(fork).getHeadCommit("accepted"))
                    .isEqualTo(forkCommit.commitId());
            assertThat(system.getHeadCommit("accepted")).isEqualTo(systemHead);
            assertThat(system.getDslAtHead("accepted")).isEqualTo("# system");
        }
    }

    @Test
    void noOpDoesNotAppendACommitOrMoveTheBranch() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var repository = factory.resolveRepository(WORKSPACE);
            String head = repository.commitDsl("review", "# original", "seed", "Seed");

            assertThat(new DslWorkspaceVersionAdapter(factory).openVersion(WORKSPACE, head)
                    .verifyExpectedHead()).isEqualTo(head);

            assertThat(repository.getCommitCount("review")).isEqualTo(1);
            assertThat(repository.getHeadCommit("review")).isEqualTo(head);
        }
    }

    @Test
    void readOnlyVersionCanBeReadButNeverCommitted() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var context = RepositoryContext.centralRead("selected", "accepted", "reader");
            var repository = factory.resolveRepository(context);
            String head = repository.commitDsl("accepted", "# original", "seed", "Seed");
            var version = new DslWorkspaceVersionAdapter(factory).openVersion(context, head);

            assertThat(version.readDsl()).isEqualTo("# original");
            assertThat(version.verifyExpectedHead()).isEqualTo(head);
            assertThatThrownBy(() -> version.commit("# forbidden", "Forbidden"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("read-only");
            assertThat(repository.getHeadCommit("accepted")).isEqualTo(head);
            assertThat(repository.getCommitCount("accepted")).isEqualTo(1);
        }
    }

    @Test
    void malformedHeadsAndMissingContextFailBeforeRepositoryResolution() {
        var factory = mock(DslGitRepositoryFactory.class);
        var port = new DslWorkspaceVersionAdapter(factory);
        for (String head : List.of(" ", "main", "abc123", "z".repeat(40))) {
            assertThatThrownBy(() -> port.openVersion(WORKSPACE, head))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> port.openVersion(null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("context");
        assertThatThrownBy(() -> new DslWorkspaceVersionAdapter(null))
                .isInstanceOf(NullPointerException.class).hasMessage("repositories");
        verifyNoInteractions(factory);
    }

    @Test
    void missingCommitIsAnErrorNotAnEmptyVersion() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var version = new DslWorkspaceVersionAdapter(factory).openVersion(WORKSPACE, "a".repeat(40));
            assertThatThrownBy(version::readDsl).isInstanceOf(IOException.class);
        }
    }

    @Test
    void existingCommitWithoutDslRetainsTheEmptyDocumentContract() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var repository = factory.resolveRepository(WORKSPACE);
            var git = repository.getGitRepository();
            ObjectId head;
            try (var inserter = git.newObjectInserter()) {
                var commit = new CommitBuilder();
                commit.setTreeId(inserter.insert(new TreeFormatter()));
                var actor = new PersonIdent("seed", "seed@taxonomy.local");
                commit.setAuthor(actor);
                commit.setCommitter(actor);
                commit.setMessage("Empty tree");
                head = inserter.insert(commit);
                inserter.flush();
            }
            var update = git.updateRef(Constants.R_HEADS + "review");
            update.setExpectedOldObjectId(ObjectId.zeroId());
            update.setNewObjectId(head);
            assertThat(update.update()).isEqualTo(RefUpdate.Result.NEW);

            var version = new DslWorkspaceVersionAdapter(factory).openVersion(WORKSPACE, head.name());
            assertThat(version.readDsl()).isEmpty();
            assertThat(version.verifyExpectedHead()).isEqualTo(head.name());
        }
    }

    @Test
    void concurrentWritersCannotBothAdvanceTheSameExpectedVersion() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var repository = factory.resolveRepository(WORKSPACE);
            String head = repository.commitDsl("review", "# original", "seed", "Seed");
            var version = new DslWorkspaceVersionAdapter(factory).openVersion(WORKSPACE, head);
            var start = new CountDownLatch(1);
            // ExecutorService.close() waits indefinitely after a writer deadlocks.
            // Daemon workers and explicit cancellation let CI report the timeout.
            var executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
            try {
                var first = executor.submit(() -> competingCommit(version, start, "first"));
                var second = executor.submit(() -> competingCommit(version, start, "second"));
                start.countDown();

                assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(true, false);
            } finally {
                executor.shutdownNow();
            }
            assertThat(repository.getCommitCount("review")).isEqualTo(2);
        }
    }

    @Test
    void springInjectsTheWorkspacePortIntoTheRelationCommandService() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DslGitRepositoryFactory.class, () -> new DslGitRepositoryFactory(null));
            context.register(DslWorkspaceVersionAdapter.class, ArchitectureRelationGitCommandService.class);
            context.refresh();

            assertThat(context.getBean(WorkspaceDslVersionPort.class))
                    .isSameAs(context.getBean(DslWorkspaceVersionAdapter.class));
            assertThat(context.getBean(ArchitectureRelationGitCommandService.class)).isNotNull();
        }
    }

    private static boolean competingCommit(WorkspaceDslVersionPort.ExactVersion version,
                                           CountDownLatch start, String contender) throws Exception {
        start.await();
        try {
            version.commit("# " + contender, contender);
            return true;
        } catch (BranchHeadConflictException conflict) {
            return false;
        }
    }

    private static final class CountingFactory extends DslGitRepositoryFactory {
        private int resolutions;

        private CountingFactory() {
            super(null);
        }

        @Override
        public DslGitRepository resolveRepository(RepositoryContext context) {
            resolutions++;
            return super.resolveRepository(context);
        }
    }
}
