package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ArchitectureEditorVersionContextTest {
    private final RepositoryContext context = RepositoryContext.workspace("context-repository", "context-workspace", "draft", "alice");

    @Test void aGitVersionContextCannotBeReusedAsAWorkspaceWriteContext() throws Exception {
        try (var fixture = fixture()) {
            var created = fixture.service.execute(context, create());
            var checkpoint = fixture.service.checkpoint(context, new CreateCheckpointCommand(created.context(), metadata()));
            var version = fixture.service.read(context, checkpoint.commitId());
            assertThat(version.context().writeMode()).isEqualTo("READ_ONLY");
            var forged = new Command(version.context(), metadata(), new SemanticCommand(new DeleteArchitectureElement("arch-context")));
            assertContextChanged(() -> fixture.service.preview(context, forged));
            assertContextChanged(() -> fixture.service.execute(context, forged));
            assertThat(fixture.journal.read(context).operations()).hasSize(1);
            assertThat(fixture.service.read(context, null).dsl()).contains("Original");
        }
    }

    @Test void twoInitialGitBaselinesCannotShareOneAcceptedRevisionZeroPreview() throws Exception {
        try (var fixture = fixture()) {
            var repository = fixture.repositories.resolveRepository(context);
            String first = repository.commitDsl("draft", model("Original"), "alice", "Initial baseline");
            var command = new Command(Context.of(context, first, 0), metadata(),
                    new SemanticCommand(new UpdateArchitectureElement("arch-context", null, Map.of("title", "Intended edit"))));
            fixture.service.preview(context, command);
            repository.commitDsl("draft", model("Changed baseline"), "alice", "New baseline before the first edit");
            assertContextChanged(() -> fixture.service.preview(context, command));
            assertContextChanged(() -> fixture.service.execute(context, command));
            assertContextChanged(() -> fixture.service.checkpoint(context, new CreateCheckpointCommand(command.context(), metadata())));
            assertThat(fixture.journal.read(context)).isNull();
            assertThat(repository.getDslAtHead("draft")).contains("Changed baseline");
        }
    }

    @Test void aCheckpointDoesNotInvalidateAPreviewAtTheSameDurableSemanticRevision() throws Exception {
        try (var fixture = fixture()) {
            var created = fixture.service.execute(context, create());
            var command = new Command(created.context(), metadata(),
                    new SemanticCommand(new UpdateArchitectureElement("arch-context", null, Map.of("title", "After checkpoint"))));
            fixture.service.preview(context, command);
            var checkpoint = fixture.service.checkpoint(context, new CreateCheckpointCommand(created.context(), metadata()));
            var accepted = fixture.service.execute(context, command);
            assertThat(accepted.context().revision()).isEqualTo(2);
            assertThat(fixture.service.read(context, null).dsl()).contains("After checkpoint");
            assertThat(fixture.repositories.resolveRepository(context).getHeadCommit("draft")).isEqualTo(checkpoint.commitId());
        }
    }

    @Test void currentCentralReadReturnsExactReadOnlyGitSnapshotWithoutWorkspaceJournal() throws Exception {
        try (var fixture = fixture()) {
            var central = RepositoryContext.centralRead("central-repository", "release", "reader");
            var repository = fixture.repositories.resolveRepository(central);
            String head = repository.commitDsl("release", model("Central snapshot"), "owner", "Seed central");
            var document = fixture.service.read(central, null);
            assertThat(document.context()).isEqualTo(Context.version(central, head));
            assertThat(document.dsl()).contains("Central snapshot");
            assertThat(document.source()).isEqualTo("GIT_CHECKPOINT");
            assertThat(document.history()).isEmpty();
            assertThat(document.versions()).hasSize(1);
            assertThat(document.pendingCheckpointId()).isNull();
            assertThat(document.context().revision()).isZero();
            var exported = new ArchitectureEditorExportAdapter(fixture.service,
                    new ArchitectureEditorProjection(new com.taxonomy.export.LayeredDiagramLayoutService())).read(central, null, null);
            assertThat(exported.repositoryId()).isEqualTo(central.repositoryId());
            assertThat(exported.branch()).isEqualTo("release");
            assertThat(exported.commit()).isEqualTo(head);
            assertThat(exported.scene().nodes()).hasSize(1);
            var edit = new Command(document.context(), metadata(), new SemanticCommand(new DeleteArchitectureElement("arch-context")));
            assertThatThrownBy(() -> fixture.service.preview(central, edit)).isInstanceOfSatisfying(CommandProblem.class,
                    error -> assertThat(error.code()).isEqualTo("READ_ONLY"));
            assertThatThrownBy(() -> fixture.service.execute(central, edit)).isInstanceOfSatisfying(CommandProblem.class,
                    error -> assertThat(error.code()).isEqualTo("READ_ONLY"));
            assertThat(repository.getHeadCommit("release")).isEqualTo(head);
        }
    }

    @Test void centralRevisionZeroAndEmptyBranchRemainReadOnlyAndOtherRevisionsAreRejected() throws Exception {
        try (var fixture = fixture()) {
            var central = RepositoryContext.centralRead("empty-central", "release", "reader");
            var document = fixture.service.read(central, null, 0L);
            assertThat(document.context()).isEqualTo(Context.version(central, null));
            assertThat(document.dsl()).isEmpty();
            assertThat(document.history()).isEmpty();
            assertThat(document.versions()).isEmpty();
            assertThatThrownBy(() -> fixture.service.read(central, null, 1L)).isInstanceOfSatisfying(CommandProblem.class,
                    error -> assertThat(error.code()).isEqualTo("NOT_FOUND"));
        }
    }

    @Test void nestedPortfolioGitActionReusesTheSameLockedEditorScopeAndRetainsTheOuterRevision() throws Exception {
        try (var data = fixture()) {
            var created = data.service.execute(context, create());
            var repository = data.repositories.resolveRepository(context);
            String commit = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(8), () ->
                    data.service.version(context, "Integrate captured working copy", () ->
                            data.service.version(context, "Project reviewed portfolio", () ->
                                    repository.commitDsl("draft", model("Selected contribution"), "alice", "Apply reviewed contribution"))));
            assertThat(repository.getHeadCommit("draft")).isEqualTo(commit);
            assertThat(data.service.read(context, null).dsl()).contains("Selected contribution");
            assertThat(data.journal.read(context).state().revision()).isGreaterThan(created.context().revision());
        }
    }

    @Test void nestedVersionAuthorityCannotBeCarriedToAnotherActorBranchRepositoryOrWorkingCopy() throws Exception {
        try (var data = fixture()) {
            data.service.execute(context, create());
            var executed = new java.util.concurrent.atomic.AtomicBoolean();
            data.service.version(context, "Lock selected editor", () -> {
                var others = java.util.List.of(
                        RepositoryContext.workspace(context.repositoryId(), context.workspaceId(), context.branch(), "other"),
                        RepositoryContext.workspace(context.repositoryId(), context.workspaceId(), "other", context.username()),
                        RepositoryContext.workspace("other", context.workspaceId(), context.branch(), context.username()),
                        RepositoryContext.workspace(context.repositoryId(), "other", context.branch(), context.username()),
                        RepositoryContext.collaborativeWorkspace(context.repositoryId(), context.workspaceId(), context.branch(), context.username()));
                for (var other : others) {
                    assertThatThrownBy(() -> data.service.version(other, "Different selection", () -> {
                        executed.set(true); return null;
                    })).isInstanceOf(IllegalStateException.class);
                }
                return null;
            });
            assertThat(executed.get()).isFalse();
            assertThatThrownBy(() -> data.service.version(context, "Interrupted write", () -> {
                throw new java.io.IOException("Interrupted");
            })).isInstanceOf(java.io.IOException.class);
            data.service.version(context, "Independent later write", () -> { executed.set(true); return null; });
            assertThat(executed.get()).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(value = RepositoryScope.class, names = {"CENTRAL_WRITE", "FORK"})
    void gitVersionActionsOutsideAWorkspaceDoNotReadAWorkspaceJournal(RepositoryScope scope) throws Exception {
        try (var fixture = fixture()) {
            var selected = new RepositoryContext("versioned-repository", null, "release", "owner", scope);
            var repository = fixture.repositories.resolveRepository(selected);
            String commit = fixture.service.version(selected, "Create Git version", () ->
                    repository.commitDsl("release", model("Versioned Git model"), "owner", "Create Git version"));
            assertThat(repository.getHeadCommit("release")).isEqualTo(commit);
            assertThat(repository.getDslAtHead("release")).contains("Versioned Git model");
            assertThat(repository.getCommitCount("release")).isEqualTo(1);
            assertThat(fixture.service.read(selected, null).source()).isEqualTo("GIT_CHECKPOINT");
        }
    }

    @Test void aCentralReadSelectionCannotRunAGitVersionAction() throws Exception {
        try (var fixture = fixture()) {
            var selected = RepositoryContext.centralRead("read-only-repository", "release", "reader");
            var repository = fixture.repositories.resolveRepository(selected);
            String head = repository.commitDsl("release", model("Published model"), "owner", "Seed central");
            assertThatThrownBy(() -> fixture.service.version(selected, "Attempt central write", () ->
                    repository.commitDsl("release", model("Unauthorized change"), "reader", "Attempt central write")))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(repository.getHeadCommit("release")).isEqualTo(head);
            assertThat(repository.getCommitCount("release")).isEqualTo(1);
        }
    }

    private Command create() {
        return new Command(Context.of(context, null, 0), metadata(),
                new SemanticCommand(new CreateArchitectureElement("arch-context", "System", Map.of("title", "Original"))));
    }
    private static String model(String title) { return "element arch-context type System {\n  title: \"" + title + "\";\n}\n"; }
    private static EditorPersistenceFixture fixture() { return new EditorPersistenceFixture("jdbc:hsqldb:mem:version-context-" + UUID.randomUUID() + ";shutdown=true"); }
    private static Metadata metadata() { String id = UUID.randomUUID().toString(); return new Metadata(id, id, id, "Version context contract"); }
    private static void assertContextChanged(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(CommandProblem.class, error -> assertThat(error.code()).isEqualTo("CONTEXT_CHANGED"));
    }
}
