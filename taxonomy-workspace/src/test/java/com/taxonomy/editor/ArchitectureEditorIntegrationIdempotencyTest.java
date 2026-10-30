package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand.UpdateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ArchitectureEditorIntegrationIdempotencyTest {
    private static final String BASE = "element component type Component {\n title: \"Unchanged integration\";\n}\n";
    private final RepositoryContext scope = RepositoryContext.workspace("noop-repository", "noop-workspace", "draft", "alice");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedIntegrationRetainsItsCommandReceiptBeforeAndAfterCheckpointCompletion(boolean completed) throws Exception {
        try (var fixture = new EditorPersistenceFixture("jdbc:hsqldb:mem:noop-integration-" + UUID.randomUUID() + ";shutdown=true")) {
            var git = fixture.repositories.resolveRepository(scope);
            git.commitDsl("draft", BASE, "alice", "Baseline");
            Context expected = fixture.service.read(scope, null).context();
            Metadata operation = metadata(), checkpoint = metadata();
            Context accepted = integrate(fixture, expected, operation, "a".repeat(64), checkpoint);
            if (completed) fixture.service.checkpoint(scope, new CreateCheckpointCommand(accepted, checkpoint));

            Context replayed = assertDoesNotThrow(() -> integrate(fixture, expected, operation, "a".repeat(64), checkpoint));
            assertThat(replayed.revision()).isEqualTo(1).isEqualTo(accepted.revision());
            var receipt = fixture.journal.operation(scope, operation.commandId());
            assertThat(receipt.beforeDsl()).isEqualTo(BASE);
            assertThat(receipt.afterDsl()).isEqualTo(BASE);
            assertThat(receipt.affectedIds()).isEmpty();
            assertThat(fixture.journal.read(scope).operations()).hasSize(1);
            assertThatThrownBy(() -> integrate(fixture, expected, operation, "b".repeat(64), checkpoint))
                    .isInstanceOfSatisfying(CommandProblem.class, problem -> assertThat(problem.code()).isEqualTo("COMMAND_ID_REUSED"));

            var published = fixture.service.checkpoint(scope, new CreateCheckpointCommand(replayed, checkpoint));
            assertThat(git.getDslAtCommit(published.commitId())).isEqualTo(BASE);
            assertThat(git.getDslHistory("draft")).hasSize(1);
            assertThat(integrate(fixture, expected, operation, "a".repeat(64), checkpoint)).isEqualTo(published.context());
            assertThat(fixture.service.integrationCheckpointForOperation(scope, operation.commandId())).isPresent();
            assertThat(fixture.journal.read(scope).operations()).hasSize(1);
            assertThat(fixture.journal.checkpoints(scope)).hasSize(1);

            var changed = fixture.service.execute(scope, new Command(published.context(), metadata(),
                    new SemanticCommand(new UpdateArchitectureElement("component", null, Map.of("title", "Changed later")))));
            fixture.service.checkpoint(scope, new CreateCheckpointCommand(changed.context(), metadata()));
            assertThat(fixture.service.integrationCheckpointForOperation(scope, operation.commandId())).isEmpty();
        }
    }

    @Test
    void completedIntegrationReplayAfterNewerCheckpointReturnsOriginalReceipt() throws Exception {
        try (var fixture = new EditorPersistenceFixture("jdbc:hsqldb:mem:historical-integration-" + UUID.randomUUID() + ";shutdown=true")) {
            var git = fixture.repositories.resolveRepository(scope);
            git.commitDsl("draft", BASE, "alice", "Baseline");
            Context originalContext = fixture.service.read(scope, null).context();
            Metadata operation = metadata(), originalCheckpoint = metadata();
            Context accepted = integrate(fixture, originalContext, operation, "a".repeat(64), originalCheckpoint);
            var original = fixture.service.checkpoint(scope, new CreateCheckpointCommand(accepted, originalCheckpoint));
            var changed = fixture.service.execute(scope, new Command(original.context(), metadata(),
                    new SemanticCommand(new UpdateArchitectureElement("component", null, Map.of("title", "Changed later")))));
            var newer = fixture.service.checkpoint(scope, new CreateCheckpointCommand(changed.context(), metadata()));
            String currentSource = fixture.service.read(scope, null).dsl();
            assertThat(fixture.service.integrationCheckpointForOperation(scope, operation.commandId())).isEmpty();

            Context replayed = integrate(fixture, originalContext, operation, "a".repeat(64), originalCheckpoint);
            var receipt = assertDoesNotThrow(() -> fixture.service.checkpoint(scope, new CreateCheckpointCommand(replayed, originalCheckpoint)));
            assertThat(receipt.context()).isEqualTo(original.context());
            assertThat(receipt.commitId()).isEqualTo(original.commitId());
            assertThat(receipt.commandId()).isEqualTo(originalCheckpoint.commandId());
            assertThat(receipt.replayed()).isTrue();
            assertThat(fixture.service.read(scope, null).context()).isEqualTo(newer.context());
            assertThat(fixture.service.read(scope, null).dsl()).isEqualTo(currentSource);
            assertThat(fixture.service.integrationCheckpointForOperation(scope, operation.commandId())).isEmpty();
            assertThat(fixture.journal.read(scope).operations()).hasSize(2);
            assertThat(fixture.journal.checkpoints(scope)).hasSize(2);
            assertThat(git.getDslHistory("draft")).hasSize(2);
        }
    }

    private Context integrate(EditorPersistenceFixture fixture, Context expected, Metadata operation,
                              String fingerprint, Metadata checkpoint) {
        return new TransactionTemplate(new JpaTransactionManager(fixture.factory)).execute(status -> {
            try {
                return fixture.service.acceptIntegration(scope, expected, operation, fingerprint, List.of(), source -> source, checkpoint);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    private static Metadata metadata() {
        String id = UUID.randomUUID().toString();
        return new Metadata(id, id, id, "Unchanged explicit integration");
    }
}
