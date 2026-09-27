package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;

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
