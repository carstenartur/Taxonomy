package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.RebuildResult;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArchitectureEditorServiceTest {
    private final DslGitRepositoryFactory repositories = new DslGitRepositoryFactory(null);
    private final RelationBranchProjectionRebuildService rebuild = mock(RelationBranchProjectionRebuildService.class);
    private final RelationBranchProjectionReadinessService readiness = mock(RelationBranchProjectionReadinessService.class);
    private ArchitectureEditorService service;
    private final RepositoryContext alice = RepositoryContext.workspace("repo-a", "workspace-a1", "draft", "alice");
    private static final String SEED = "element arch-existing type System {\n  title: \"Original\";\n}\n";

    @BeforeEach
    void setUp() throws Exception {
        service = new ArchitectureEditorService(repositories, rebuild, readiness);
        when(rebuild.rebuild(any())).thenAnswer(invocation -> {
            RepositoryContext context = invocation.getArgument(0);
            return new RebuildResult(context.repositoryId(), context.workspaceId(), context.branch(),
                    repositories.resolveRepository(context).getHeadCommit(context.branch()), 0);
        });
        when(readiness.inspect(any())).thenAnswer(invocation -> {
            RepositoryContext context = invocation.getArgument(0);
            String head = repositories.resolveRepository(context).getHeadCommit(context.branch());
            return new Readiness(ReadinessState.READY, head, head, List.of());
        });
    }

    @AfterEach
    void close() { repositories.close(); }

    @Test
    void previewIsReadOnlyAndCommitReplayAndUndoSurviveServiceRecreation() throws Exception {
        String original = seed(alice);
        Command command = command(alice, original, new SemanticCommand(new UpdateArchitectureElement("arch-existing", null, Map.of("title", "Changed"))));
        assertThat(service.preview(alice, command).change().changedIds()).containsExactly("element:arch-existing");
        assertThat(repositories.resolveRepository(alice).getHeadCommit("draft")).isEqualTo(original);
        verifyNoInteractions(rebuild);
        Accepted accepted = service.execute(alice, command);
        assertThat(accepted.commitCreated()).isTrue();
        assertThat(accepted.projectionState()).isEqualTo("READY");
        service = new ArchitectureEditorService(repositories, rebuild, readiness);
        Accepted replay = service.execute(alice, command);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.context().commit()).isEqualTo(accepted.context().commit());
        assertThat(repositories.resolveRepository(alice).getCommitCount("draft")).isEqualTo(2);
        var history = service.read(alice, null).history();
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().actor()).isEqualTo("alice");
        assertThat(history.getFirst().commandId()).isEqualTo(command.metadata().commandId());
        Accepted undone = service.execute(alice, command(alice, accepted.context().commit(), new UndoArchitectureCommand(accepted.context().commit())));
        assertThat(service.read(alice, null).dsl()).isEqualTo(SEED);
        service = new ArchitectureEditorService(repositories, rebuild, readiness);
        Accepted redone = service.execute(alice, command(alice, undone.context().commit(), new RedoArchitectureCommand(undone.context().commit())));
        assertThat(service.read(alice, null).dsl()).contains("title: \"Changed\";");
        assertThat(repositories.resolveRepository(alice).getCommitCount("draft")).isEqualTo(4);
        assertThat(service.read(alice, null).history()).extracting(ArchitectureEditorService.HistoryEntry::kind)
                .containsExactly("REDO", "UNDO", "UpdateArchitectureElement");
        assertThat(service.read(alice, original).dsl()).isEqualTo(SEED);
        assertThat(redone.context().commit()).isNotEqualTo(accepted.context().commit());
    }

    @Test
    void staleHeadAndReusedCommandIdentityCannotOverwriteAcceptedChanges() throws Exception {
        String original = seed(alice);
        Command first = command(alice, original, new SemanticCommand(new UpdateArchitectureElement("arch-existing", null, Map.of("title", "First"))));
        Accepted accepted = service.execute(alice, first);
        clearInvocations(rebuild);
        assertThatThrownBy(() -> service.execute(alice, command(alice, original,
                new SemanticCommand(new UpdateArchitectureElement("arch-existing", null, Map.of("title", "Stale"))))))
                .isInstanceOf(BranchHeadConflictException.class);
        Command reused = new Command(first.context(), first.metadata(), new SemanticCommand(new DeleteArchitectureElement("arch-existing")));
        assertCode(() -> service.execute(alice, reused), "COMMAND_ID_REUSED");
        verifyNoInteractions(rebuild);
        assertThat(repositories.resolveRepository(alice).getHeadCommit("draft")).isEqualTo(accepted.context().commit());
    }

    @Test
    void exactRepositoryWorkspaceBranchAndActorContextIsRequired() throws Exception {
        List<RepositoryContext> contexts = List.of(alice,
                RepositoryContext.workspace("repo-a", "workspace-a2", "draft", "alice"),
                RepositoryContext.centralRead("repo-a", "main", "alice"),
                RepositoryContext.centralRead("repo-a", "draft", "alice"),
                RepositoryContext.centralRead("repo-b", "main", "alice"));
        for (RepositoryContext context : contexts) seed(context);
        String original = repositories.resolveRepository(alice).getHeadCommit("draft");
        Accepted accepted = service.execute(alice, command(alice, original,
                new SemanticCommand(new UpdateArchitectureElement("arch-existing", null, Map.of("title", "Private")))));
        for (RepositoryContext other : contexts.subList(1, contexts.size())) {
            assertThat(service.read(other, null).dsl()).isEqualTo(SEED);
            assertThat(service.read(other, null).history()).isEmpty();
            assertCode(() -> service.read(other, accepted.context().commit()), "NOT_FOUND");
            assertCode(() -> service.execute(other, command(alice, original, new SemanticCommand(new DeleteArchitectureElement("arch-existing")))), "CONTEXT_CHANGED");
        }
        RepositoryContext central = contexts.get(2);
        assertCode(() -> service.execute(central, command(central, service.read(central, null).context().commit(),
                new SemanticCommand(new DeleteArchitectureElement("arch-existing")))), "READ_ONLY");
        Context forged = new Context("repo-a", "workspace-a1", "draft", accepted.context().commit(), "bob", "PRIVATE_WORKSPACE");
        Command legitimate = command(alice, accepted.context().commit(), new UndoArchitectureCommand(accepted.context().commit()));
        assertCode(() -> service.execute(alice, new Command(forged, legitimate.metadata(), legitimate.operation())), "CONTEXT_CHANGED");
    }

    @Test
    void projectionFailureReturnsGitAuthorityAndCanBeRebuiltWithoutAnotherCommit() throws Exception {
        String head = seed(alice);
        when(rebuild.rebuild(alice)).thenThrow(new IllegalStateException("projection unavailable"));
        Command command = command(alice, head, new SemanticCommand(new UpdateArchitectureElement("arch-existing", null, Map.of("title", "Durable"))));
        Accepted accepted = service.execute(alice, command);
        assertThat(accepted.projectionState()).isEqualTo("REBUILD_REQUIRED");
        assertThat(repositories.resolveRepository(alice).getDslAtCommit(accepted.context().commit())).contains("Durable");
        assertThat(service.execute(alice, command).replayed()).isTrue();
        doReturn(new RebuildResult("repo-a", "workspace-a1", "draft", accepted.context().commit(), 0)).when(rebuild).rebuild(alice);
        assertThat(service.rebuild(alice, accepted.context())).isEqualTo("READY");
        assertThat(repositories.resolveRepository(alice).getCommitCount("draft")).isEqualTo(2);
    }

    @Test
    void dependencyAddedLaterBlocksUndoWithoutSkippingTheTarget() throws Exception {
        String head = seed(alice);
        Accepted created = service.execute(alice, command(alice, head,
                new SemanticCommand(new CreateArchitectureElement("arch-component", "Component", Map.of("title", "Component")))));
        Accepted related = service.execute(alice, command(alice, created.context().commit(),
                new SemanticCommand(new CreateArchitectureRelation(new RelationKey("arch-existing", "CONTAINS", "arch-component"), null))));
        assertCode(() -> service.preview(alice, command(alice, related.context().commit(), new UndoArchitectureCommand(created.context().commit()))), "UNDO_CONFLICT");
        assertThat(repositories.resolveRepository(alice).getCommitCount("draft")).isEqualTo(3);
    }

    @Test
    void concurrentWritersAtOneExpectedHeadAcceptExactlyOneCommand() throws Exception {
        String head = seed(alice);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> write = () -> {
                start.await();
                try {
                    service.execute(alice, command(alice, head, new SemanticCommand(new CreateArchitectureElement(
                            "arch-" + UUID.randomUUID(), "System", Map.of("title", "Concurrent")))));
                    return true;
                } catch (BranchHeadConflictException expected) { return false; }
            };
            var first = executor.submit(write);
            var second = executor.submit(write);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(repositories.resolveRepository(alice).getCommitCount("draft")).isEqualTo(2);
    }

    @Test
    void personalHistoryAndUndoDoNotExposeAnotherActorsCommands() throws Exception {
        String head = seed(alice);
        Accepted accepted = service.execute(alice, command(alice, head,
                new SemanticCommand(new UpdateArchitectureElement("arch-existing", null, Map.of("title", "Personal")))));
        RepositoryContext otherActor = RepositoryContext.workspace("repo-a", "workspace-a1", "draft", "bob");
        assertThat(service.read(otherActor, null).history()).isEmpty();
        assertCode(() -> service.preview(otherActor, command(otherActor, accepted.context().commit(),
                new UndoArchitectureCommand(accepted.context().commit()))), "NOT_FOUND");
        assertThat(service.read(alice, null).history()).hasSize(1);
    }

    @Test
    void gitStorageFailureLeavesBranchAndProjectionUnchanged() throws Exception {
        String head = seed(alice);
        var source = repositories.resolveRepository(alice);
        var failing = spy(source);
        doThrow(new java.io.IOException("Git object unavailable")).when(failing).getDslAtCommit(head);
        var factory = mock(DslGitRepositoryFactory.class);
        when(factory.resolveRepository(alice)).thenReturn(failing);
        var editor = new ArchitectureEditorService(factory, rebuild, readiness);
        assertThatThrownBy(() -> editor.execute(alice, command(alice, head,
                new SemanticCommand(new UpdateArchitectureElement("arch-existing", null, Map.of("title", "Unaccepted"))))))
                .isInstanceOf(java.io.IOException.class);
        assertThat(source.getHeadCommit("draft")).isEqualTo(head);
        assertThat(source.getDslAtHead("draft")).isEqualTo(SEED);
        assertThat(source.getCommitCount("draft")).isEqualTo(1);
        verifyNoInteractions(rebuild, readiness);
    }

    @Test
    void mergeCannotBeAnInverseTargetEvenWhenItCopiesEditorFooters() throws Exception {
        String original = seed(alice);
        Accepted accepted = service.execute(alice, command(alice, original,
                new SemanticCommand(new UpdateArchitectureElement("arch-existing", null, Map.of("title", "Updated")))));
        var repository = repositories.resolveRepository(alice).getGitRepository();
        String mergeId;
        try (var walk = new org.eclipse.jgit.revwalk.RevWalk(repository);
             var inserter = repository.newObjectInserter()) {
            var prior = walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(accepted.context().commit()));
            var merge = new org.eclipse.jgit.lib.CommitBuilder();
            merge.setTreeId(prior.getTree());
            merge.setParentIds(prior.getId(), org.eclipse.jgit.lib.ObjectId.fromString(original));
            merge.setAuthor(prior.getAuthorIdent());
            merge.setCommitter(prior.getCommitterIdent());
            merge.setMessage(prior.getFullMessage());
            var id = inserter.insert(merge);
            inserter.flush();
            var update = repository.updateRef("refs/heads/draft");
            update.setExpectedOldObjectId(prior.getId());
            update.setNewObjectId(id);
            assertThat(update.update()).isEqualTo(org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD);
            mergeId = id.name();
        }
        assertCode(() -> service.preview(alice, command(alice, mergeId, new UndoArchitectureCommand(mergeId))), "NOT_UNDOABLE");
        assertCode(() -> service.preview(alice, command(alice, mergeId, new RedoArchitectureCommand(mergeId))), "NOT_UNDOABLE");
        assertThat(service.read(alice, null).history()).hasSize(1);
        assertThat(repositories.resolveRepository(alice).getHeadCommit("draft")).isEqualTo(mergeId);
    }

    private String seed(RepositoryContext context) throws Exception {
        return repositories.resolveRepository(context).commitDsl(context.branch(), SEED, "seed", "Seed architecture objects");
    }

    private static Command command(RepositoryContext context, String head, Operation operation) {
        String id = UUID.randomUUID().toString();
        return new Command(Context.of(context, head), new Metadata(id, id, id, "Architecture decision"), operation);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(CommandProblem.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
