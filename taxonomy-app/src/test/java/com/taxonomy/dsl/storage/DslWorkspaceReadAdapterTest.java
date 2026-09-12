package com.taxonomy.dsl.storage;

import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildWriter;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DslWorkspaceReadAdapterTest {

    private static final RepositoryContext CONTEXT =
            RepositoryContext.workspace("repo-a", "workspace-a", "review", "alice");

    @Test
    void bindsOneRepositoryAndKeepsHistoricalReadsSeparateFromCurrentHead() throws Exception {
        try (var factory = new CountingFactory()) {
            var git = factory.resolveRepository(CONTEXT);
            String original = git.commitDsl("review", "# original", "alice", "Original");
            factory.resolutions = 0;
            var read = new DslWorkspaceReadAdapter(factory).openRead(CONTEXT);
            String next = git.commitDsl("review", "# next", "bob", "Next");

            assertThat(read.currentHead()).isEqualTo(next);
            assertThat(read.dslAtCommit(original)).contains("# original");
            assertThat(read.dslAtCommit(next)).contains("# next");
            assertThat(read.verifyExpectedHead(next)).isEqualTo(next);
            assertThat(factory.resolutions).isEqualTo(1);
            assertThat(git.getCommitCount("review")).isEqualTo(2);
        }
    }

    @Test
    void missingDslAndAValidEmptyFileRemainDifferent() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var git = factory.resolveRepository(CONTEXT);
            String missing = commitWithoutDsl(git);
            String empty = git.commitDsl("review", "", "alice", "Empty DSL");
            var read = new DslWorkspaceReadAdapter(factory).openRead(CONTEXT);

            assertThat(read.dslAtCommit(missing)).isEmpty();
            assertThat(read.dslAtCommit(empty)).contains("");
            assertThat(read.currentHead()).isEqualTo(empty);
        }
    }

    @Test
    void absentBranchReadAndVerificationNeverCreateHistory() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var read = new DslWorkspaceReadAdapter(factory).openRead(CONTEXT);
            assertThat(read.currentHead()).isNull();
            assertThat(read.verifyExpectedHead(null)).isNull();
            assertThat(factory.resolveRepository(CONTEXT).getBranchNames()).isEmpty();
        }
    }

    @Test
    void staleExpectationRetainsAllConflictMetadataWithoutMovingTheBranch() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var git = factory.resolveRepository(CONTEXT);
            String previous = git.commitDsl("review", "# previous", "alice", "Previous");
            var read = new DslWorkspaceReadAdapter(factory).openRead(CONTEXT);
            String current = git.commitDsl("review", "# current", "bob", "Current");

            assertThatThrownBy(() -> read.verifyExpectedHead(previous))
                    .isInstanceOfSatisfying(BranchHeadConflictException.class, conflict -> {
                        assertThat(conflict.getBranch()).isEqualTo("review");
                        assertThat(conflict.getExpectedHeadCommit()).isEqualTo(previous);
                        assertThat(conflict.getActualHeadCommit()).isEqualTo(current);
                    });
            assertThat(git.getHeadCommit("review")).isEqualTo(current);
            assertThat(git.getCommitCount("review")).isEqualTo(2);
        }
    }

    @Test
    void missingObjectsAndMalformedIdsAreNotReportedAsMissingFiles() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var read = new DslWorkspaceReadAdapter(factory).openRead(CONTEXT);
            assertThatThrownBy(() -> read.dslAtCommit("a".repeat(40)))
                    .isInstanceOf(IOException.class);
            for (String malformed : List.of("review", "abc123", " ", "z".repeat(40))) {
                assertThatThrownBy(() -> read.dslAtCommit(malformed))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> read.dslAtCommit(null))
                    .isInstanceOf(NullPointerException.class).hasMessage("commitId");
        }
    }

    @Test
    void scopesAndBranchesNeverFallBackToAnUnselectedRepository() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var port = new DslWorkspaceReadAdapter(factory);
            factory.getSystemRepository().commitDsl("review", "# system", "system", "System");
            String workspaceHead = factory.resolveRepository(CONTEXT)
                    .commitDsl("review", "# workspace", "alice", "Workspace");
            var central = RepositoryContext.centralRead("selected", "review", "reader");
            String centralHead = factory.resolveRepository(central)
                    .commitDsl("review", "# central", "system", "Central");
            var fork = new RepositoryContext("fork-a", null, "review", "alice", RepositoryScope.FORK);
            String forkHead = factory.resolveRepository(fork)
                    .commitDsl("review", "# fork", "alice", "Fork");

            assertThat(port.openRead(CONTEXT).currentHead()).isEqualTo(workspaceHead);
            assertThat(port.openRead(central).currentHead()).isEqualTo(centralHead);
            assertThat(port.openRead(central).verifyExpectedHead(centralHead)).isEqualTo(centralHead);
            assertThat(port.openRead(fork).currentHead()).isEqualTo(forkHead);
            assertThat(port.openRead(RepositoryContext.workspace(
                    "repo-a", "workspace-b", "review", "bob")).currentHead()).isNull();
            assertThat(port.openRead(RepositoryContext.workspace(
                    "repo-a", "workspace-a", "other", "alice")).currentHead()).isNull();
            assertThat(port.openRead(RepositoryContext.centralRead(
                    "unselected", "review", "reader")).currentHead()).isNull();
        }
    }

    @Test
    void missingContextFailsBeforeRepositoryResolution() {
        var factory = mock(DslGitRepositoryFactory.class);
        assertThatThrownBy(() -> new DslWorkspaceReadAdapter(factory).openRead(null))
                .isInstanceOf(NullPointerException.class).hasMessage("context");
        assertThatThrownBy(() -> new DslWorkspaceReadAdapter(null))
                .isInstanceOf(NullPointerException.class).hasMessage("repositories");
        verifyNoInteractions(factory);
    }

    @Test
    void springWiresBothProjectionReadersThroughTheReadOnlyPort() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DslGitRepositoryFactory.class, () -> new DslGitRepositoryFactory(null));
            context.registerBean(RelationBranchProjectionRebuildWriter.class,
                    () -> mock(RelationBranchProjectionRebuildWriter.class));
            context.registerBean(RelationDecisionProjectionRepository.class,
                    () -> mock(RelationDecisionProjectionRepository.class));
            context.registerBean(RelationDecisionProjectionCheckpointRepository.class,
                    () -> mock(RelationDecisionProjectionCheckpointRepository.class));
            context.register(DslWorkspaceReadAdapter.class,
                    RelationBranchProjectionRebuildService.class,
                    RelationBranchProjectionReadinessService.class);
            context.refresh();

            assertThat(context.getBean(WorkspaceDslReadPort.class))
                    .isSameAs(context.getBean(DslWorkspaceReadAdapter.class));
            assertThat(context.getBean(RelationBranchProjectionRebuildService.class)).isNotNull();
            assertThat(context.getBean(RelationBranchProjectionReadinessService.class)
                    .readCurrentHead(CONTEXT)).isNull();
        }
    }

    private static String commitWithoutDsl(DslGitRepository dsl) throws Exception {
        var git = dsl.getGitRepository();
        ObjectId head;
        try (var inserter = git.newObjectInserter()) {
            var commit = new CommitBuilder();
            commit.setTreeId(inserter.insert(new TreeFormatter()));
            var actor = new PersonIdent("seed", "seed@taxonomy.local");
            commit.setAuthor(actor);
            commit.setCommitter(actor);
            commit.setMessage("No DSL file");
            head = inserter.insert(commit);
            inserter.flush();
        }
        var update = git.updateRef("refs/heads/review");
        update.setExpectedOldObjectId(ObjectId.zeroId());
        update.setNewObjectId(head);
        assertThat(update.update()).isEqualTo(RefUpdate.Result.NEW);
        return head.name();
    }

    private static final class CountingFactory extends DslGitRepositoryFactory {
        private int resolutions;
        private CountingFactory() { super(null); }
        @Override public DslGitRepository resolveRepository(RepositoryContext context) {
            resolutions++;
            return super.resolveRepository(context);
        }
    }
}
