package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureElement;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.workspace.service.RepositoryContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditorJournalFailureAtomicityTest {
    @Test void aFailedVersionActionIsRolledBackWithoutAutomaticallyRepeatingItsGitSideEffect() throws Exception {
        try (var fixture = new EditorPersistenceFixture("jdbc:hsqldb:mem:editor-failure-" + UUID.randomUUID() + ";shutdown=true")) {
            var context = RepositoryContext.workspace("failure-repository", "failure-workspace", "draft", "alice");
            fixture.service.execute(context, new Command(Context.of(context, null, 0), metadata(),
                    new SemanticCommand(new CreateArchitectureElement("arch-original", "System", Map.of("title", "Original")))));
            var before = fixture.journal.read(context);
            var repository = fixture.repositories.resolveRepository(context);
            AtomicInteger publications = new AtomicInteger();

            assertThatThrownBy(() -> fixture.journal.locked(context, before.state(), session -> {
                try {
                    repository.commitDsl("draft", before.state().dsl(), "alice", "Explicit architecture version");
                    publications.incrementAndGet();
                } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                session.append(metadata(), "alice", "VERSION_IMPORT", null, "f".repeat(64), "", List.of("element:arch-original"));
                throw new PersistenceException("Database failed after successful Git publication");
            })).isInstanceOf(PersistenceException.class);

            assertThat(publications.get()).isEqualTo(1);
            assertThat(repository.getDslHistory("draft")).hasSize(1);
            assertThat(fixture.journal.read(context)).isEqualTo(before);
        }
    }

    private static Metadata metadata() {
        String id = UUID.randomUUID().toString();
        return new Metadata(id, id, id, "Failure recovery contract");
    }
}
