package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchitectureEditorServiceTest {
    private EditorPersistenceFixture fixture;
    private ArchitectureEditorService service;
    private final RepositoryContext alice = RepositoryContext.workspace("repo-a", "workspace-a1", "draft", "alice");
    private static final String SEED = "// source before editor\nelement arch-existing type System {\n  title: \"Original\";\n}\n";

    @BeforeEach void setUp() throws Exception {
        fixture = new EditorPersistenceFixture("jdbc:hsqldb:mem:editor-" + UUID.randomUUID());
        service = fixture.service;
        fixture.repositories.resolveRepository(alice).commitDsl("draft", SEED, "seed", "Initial architecture version");
    }
    @AfterEach void close() { fixture.close(); }

    @Test void hundredMixedEditsAreJournaledWithoutMovingGitAndCheckpointsCollectTheirSnapshots() throws Exception {
        var git = fixture.repositories.resolveRepository(alice);
        String original = git.getHeadCommit("draft");
        for (int i = 0; i < 25; i++) {
            execute(new SemanticCommand(new CreateArchitectureElement("arch-child-" + i, "Component", Map.of("title", "Child " + i))));
            execute(update("Title " + i));
            RelationKey relation = new RelationKey("arch-existing", "CONTAINS", "arch-child-" + i);
            execute(new SemanticCommand(new CreateArchitectureRelation(relation, "proposed")));
            execute(new SemanticCommand(new DeleteArchitectureRelation(relation)));
        }
        assertThat(fixture.journal.read(alice).operations()).hasSize(100);
        assertThat(service.read(alice, null).context().revision()).isEqualTo(100);
        assertThat(git.getCommitCount("draft")).isEqualTo(1);
        assertThat(git.getHeadCommit("draft")).isEqualTo(original);
        var request = new CreateCheckpointCommand(service.read(alice, null).context(), metadata());
        var first = service.checkpoint(alice, request);
        assertThat(first.commitCreated()).isTrue();
        assertThat(first.fromRevision()).isZero();
        assertThat(first.throughRevision()).isEqualTo(100);
        assertThat(service.checkpoint(alice, request).replayed()).isTrue();
        assertThat(git.getCommitCount("draft")).isEqualTo(2);
        assertThat(git.getDslAtCommit(first.commitId())).isEqualTo(service.read(alice, null).dsl());
        execute(update("After checkpoint"));
        assertThat(git.getHeadCommit("draft")).isEqualTo(first.commitId());
        assertThat(git.getDslAtHead("draft")).doesNotContain("After checkpoint");
        var second = service.checkpoint(alice, new CreateCheckpointCommand(service.read(alice, null).context(), metadata()));
        assertThat(second.fromRevision()).isEqualTo(100);
        assertThat(second.throughRevision()).isEqualTo(101);
        assertThat(git.getDslAtHead("draft")).contains("After checkpoint");
        assertThat(git.getCommitCount("draft")).isEqualTo(3);
        assertThat(fixture.journal.read(alice).operations()).hasSize(101);
        var unchanged = service.checkpoint(alice, new CreateCheckpointCommand(service.read(alice, null).context(), metadata()));
        assertThat(unchanged.commitCreated()).isFalse();
        assertThat(unchanged.commitId()).isEqualTo(second.commitId());
        assertThat(git.getCommitCount("draft")).isEqualTo(3);
        try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getGitRepository())) {
            var commit = walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(second.commitId()));
            assertThat(commit.getFullMessage()).contains("Semantic-Revision: 101", "Operations-After: 100", "Operations-Through: 101", "Rationale:");
        }
    }

    @Test void previewDoesNotEvenInitializeJournalAndRetryNeverAppendsTwice() throws Exception {
        Command command = command(update("Changed"));
        service.preview(alice, command);
        assertThat(fixture.journal.read(alice)).isNull();
        Accepted accepted = service.execute(alice, command);
        assertThat(service.execute(alice, command).replayed()).isTrue();
        assertThat(fixture.journal.read(alice).operations()).hasSize(1);
        assertThat(accepted.operationId()).isEqualTo(command.metadata().commandId());
        assertThat(service.read(alice, null).dsl()).startsWith("// source before editor");
        assertCode(() -> service.execute(alice, new Command(command.context(), command.metadata(), update("Different"))), "COMMAND_ID_REUSED");
        assertThatThrownBy(() -> service.execute(alice, new Command(command.context(), metadata(), update("Stale"))))
                .isInstanceOf(EditorJournal.RevisionConflict.class);
    }

    @Test void twoIndependentWritersCannotBothAcceptTheSameSemanticRevisionIncludingInitialization() throws Exception {
        var secondService = new ArchitectureEditorService(fixture.repositories,
                new EditorJournal(fixture.factory, new org.springframework.orm.jpa.JpaTransactionManager(fixture.factory)), new ArchitectureCheckpointWriter());
        Command first = command(update("First"));
        Command second = command(update("Second"));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> attempt(service, first, start));
            var two = pool.submit(() -> attempt(secondService, second, start));
            start.countDown();
            assertThat(List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("accepted", "stale");
        }
        assertThat(fixture.journal.read(alice).operations()).hasSize(1);
    }

    private String attempt(ArchitectureEditorService writer, Command command, CountDownLatch start) throws Exception {
        start.await();
        try { writer.execute(alice, command); return "accepted"; }
        catch (EditorJournal.RevisionConflict expected) { return "stale"; }
    }

    @Test void inverseTargetsOperationsAndConflictsWithLaterIntersectingChanges() throws Exception {
        var first = execute(update("Personal"));
        execute(new SemanticCommand(new CreateArchitectureElement("arch-other", "Component", Map.of("title", "Unrelated"))));
        var undo = execute(new UndoArchitectureCommand(UUID.fromString(first.operationId())));
        assertThat(service.read(alice, null).dsl()).contains("Original", "Unrelated");
        execute(new RedoArchitectureCommand(UUID.fromString(undo.operationId())));
        assertThat(service.read(alice, null).dsl()).contains("Personal", "Unrelated");
        var target = execute(update("Target"));
        execute(update("Later"));
        assertCode(() -> service.preview(alice, command(new UndoArchitectureCommand(UUID.fromString(target.operationId())))), "UNDO_CONFLICT");
    }

    @Test void laterRelationTouchesEndpointAndBlocksUndoWithoutTraversingGit() throws Exception {
        execute(new SemanticCommand(new CreateArchitectureElement("arch-child", "Component", Map.of("title", "Child"))));
        var changed = execute(update("Referenced"));
        execute(new SemanticCommand(new CreateArchitectureRelation(new RelationKey("arch-existing", "CONTAINS", "arch-child"), "proposed")));
        assertCode(() -> service.preview(alice, command(new UndoArchitectureCommand(UUID.fromString(changed.operationId())))), "UNDO_CONFLICT");
        assertThat(fixture.repositories.resolveRepository(alice).getCommitCount("draft")).isEqualTo(1);
    }

    @Test void actorAndContextIsolationAndReadOnlyPermissionsSurviveNewStorageBoundary() throws Exception {
        var accepted = execute(update("Private"));
        RepositoryContext bob = RepositoryContext.workspace("repo-a", "workspace-a1", "draft", "bob");
        var read = service.read(bob, null);
        assertThat(read.history()).isEmpty();
        assertCode(() -> service.preview(bob, new Command(read.context(), metadata(), new UndoArchitectureCommand(UUID.fromString(accepted.operationId())))), "NOT_FOUND");
        RepositoryContext other = RepositoryContext.workspace("repo-a", "workspace-a2", "draft", "alice");
        assertThat(service.read(other, null).history()).isEmpty();
        assertThat(service.read(other, null).dsl()).isEmpty();
        assertCode(() -> service.execute(other, command(update("Forged"))), "CONTEXT_CHANGED");
        RepositoryContext central = RepositoryContext.centralRead("repo-a", "draft", "alice");
        assertCode(() -> service.execute(central, new Command(Context.of(central, null), metadata(), update("Forbidden"))), "READ_ONLY");
    }

    @Test void checkpointRecoversAfterGitSucceededAndApplicationDiedBeforeDatabaseCompletion() throws Exception {
        execute(update("Crash durable"));
        var writer = spy(new ArchitectureCheckpointWriter());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new java.io.IOException("Process died after Git ref update");
        }).when(writer).write(any(), anyString(), anyString(), any());
        var interrupted = new ArchitectureEditorService(fixture.repositories, fixture.journal, writer);
        var request = new CreateCheckpointCommand(service.read(alice, null).context(), metadata());
        assertThatThrownBy(() -> interrupted.checkpoint(alice, request)).isInstanceOf(java.io.IOException.class);
        assertThat(fixture.journal.read(alice).state().pendingCheckpoint()).isEqualTo(request.metadata().commandId());
        assertCode(() -> execute(update("Blocked while recovery pending")), "CHECKPOINT_PENDING");
        var recovered = service.resumeCheckpoint(alice);
        assertThat(recovered.commitCreated()).isTrue();
        assertThat(fixture.repositories.resolveRepository(alice).getCommitCount("draft")).isEqualTo(2);
        assertThat(fixture.journal.read(alice).operations()).hasSize(1);
        assertThat(fixture.journal.read(alice).state().pendingCheckpoint()).isNull();
        assertThat(service.checkpoint(alice, request).commitId()).isEqualTo(recovered.commitId());
    }

    @Test void versionBranchCompareRestoreAndBothProjectionSourcesRetainTheirMeaning() throws Exception {
        var git = fixture.repositories.resolveRepository(alice);
        String initial = git.getHeadCommit("draft");
        execute(update("Branch snapshot"));
        service.version(alice, "Create architecture baseline", () -> git.createBranch("baseline", "draft"));
        assertThat(git.getDslAtHead("baseline")).contains("Branch snapshot");
        String version = git.getHeadCommit("draft");
        execute(update("Uncheckpointed"));
        var projector = new ArchitectureEditorProjection(new com.taxonomy.export.LayeredDiagramLayoutService());
        assertThat(projector.project(service.read(alice, null), true).model().getElements().getFirst().getTitle()).isEqualTo("Uncheckpointed");
        assertThat(projector.project(service.read(alice, version), true).model().getElements().getFirst().getTitle()).isEqualTo("Branch snapshot");
        assertThat(service.rebuild(alice, service.read(alice, null).context())).isEqualTo("READY");
        service.version(alice, "Restore architecture as new version", () -> {
            try { return git.restore(initial, "draft"); }
            catch (Exception e) { throw new java.io.IOException(e); }
        });
        assertThat(service.read(alice, null).dsl()).isEqualTo(SEED);
        assertThat(service.read(alice, null).history().getFirst().kind()).isEqualTo("VERSION_IMPORT");
        assertThat(git.getHeadCommit("draft")).isNotEqualTo(initial);
        assertThat(git.textDiff(initial, version)).contains("Branch snapshot");
        assertThat(service.read(alice, version).history()).isEmpty();
        assertThat(service.read(alice, null).versions()).hasSize(4);
    }

    private Accepted execute(Operation operation) throws Exception { return service.execute(alice, command(operation)); }
    private Command command(Operation operation) throws Exception { return new Command(service.read(alice, null).context(), metadata(), operation); }
    static Metadata metadata() { String id = UUID.randomUUID().toString(); return new Metadata(id, id, id, "Architecture decision"); }
    private static Operation update(String title) { return new SemanticCommand(new UpdateArchitectureElement("arch-existing", null, Map.of("title", title))); }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(CommandProblem.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
