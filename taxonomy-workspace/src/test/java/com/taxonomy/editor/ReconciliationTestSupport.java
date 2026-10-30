package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand.UpdateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.ArchitectureEditorService.*;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

abstract class ReconciliationTestSupport {
    static final String FIRST = "element first type Component {\n  title: \"First\";\n  x-foreign: \"retain me\";\n}\n";
    static final String SECOND = "element second type Component {\n  title: \"Second\";\n}\n";
    static final String THIRD = "element third type Component {\n  title: \"Third\";\n}\n";
    static final String BASE = "# Original document annotation\n" + FIRST + SECOND + THIRD;
    final RepositoryContext context = RepositoryContext.workspace("reconciliation-repo", "private-workspace", "draft", "alice");
    EditorPersistenceFixture fixture;
    ArchitectureEditorService service;

    @BeforeEach void fixture() throws Exception {
        fixture = new EditorPersistenceFixture("jdbc:hsqldb:mem:reconciliation-" + UUID.randomUUID());
        service = fixture.service;
        seed(BASE);
    }
    @AfterEach void closeFixture() { fixture.close(); }

    void seed(String source) throws Exception { fixture.repositories.resolveRepository(context).commitDsl("draft", source, "alice", "Fixture baseline"); }
    String head() throws Exception { return fixture.repositories.resolveRepository(context).getHeadCommit("draft"); }
    String source() throws Exception { return service.read(context, null).dsl(); }
    Context retained() throws Exception { return service.read(context, null).context(); }
    String advance(String incoming) throws Exception {
        return fixture.repositories.resolveRepository(context).commitDsl("draft", incoming, "other-author", "Incoming architecture source");
    }
    Accepted edit(String id, String title) throws Exception {
        return service.execute(context, new Command(retained(), metadata("Local architecture edit"),
                new SemanticCommand(new UpdateArchitectureElement(id, "Component", Map.of("title", title)))));
    }
    String titled(String source, String id, String title) {
        return new ArchitectureDslCommands().apply(source, new UpdateArchitectureElement(id, "Component", Map.of("title", title))).dsl();
    }
    Context localSource(String source) throws Exception {
        return integrate(service, retained(), metadata("Reviewed local source"), EditorJournal.hash(source), old -> source, null);
    }
    Context integrate(ArchitectureEditorService target, Context expected, Metadata command, String fingerprint,
                      UnaryOperator<String> transform, Metadata checkpoint) throws IOException {
        try {
            return new TransactionTemplate(new JpaTransactionManager(fixture.factory)).execute(status -> {
                try { return target.acceptIntegration(context, expected, command, fingerprint, List.of(), transform, checkpoint); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
    }
    ReconciliationPreview preview(Metadata metadata, Map<String, Resolution> decisions) throws Exception {
        return service.previewReconciliation(context, new ReconciliationRequest(retained(), metadata, decisions, null, null));
    }
    ReconciliationRequest acceptedRequest(ReconciliationPreview preview, Metadata metadata, Map<String, Resolution> decisions) {
        return new ReconciliationRequest(preview.context(), metadata, decisions, preview.headCommit(), preview.fingerprint());
    }
    Map<String, Resolution> choices(ReconciliationPreview preview, Resolution decision) {
        Map<String, Resolution> result = new LinkedHashMap<>();
        preview.conflicts().forEach(conflict -> result.put(conflict.id(), decision));
        return result;
    }
    List<String> checkpointIds() {
        try (var entityManager = fixture.factory.createEntityManager()) {
            return entityManager.createQuery("select c.commandId from EditorCheckpoint c where c.scopeId=:scope order by c.commandId", String.class)
                    .setParameter("scope", EditorJournal.scope(context)).getResultList();
        }
    }
    record Persisted(EditorJournal.Snapshot journal, List<String> checkpoints, String head) {}
    Persisted capture() throws Exception { return new Persisted(fixture.journal.read(context), checkpointIds(), head()); }
    void unchanged(Persisted before) throws Exception { assertThat(capture()).isEqualTo(before); }
    interface CheckedAction { void run() throws Exception; }
    static void rejected(String code, CheckedAction action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CommandProblem.class, error -> assertThat(error.code()).isEqualTo(code));
    }
    static Metadata metadata(String reason) {
        String id = UUID.randomUUID().toString();
        return new Metadata(id, id, id, reason);
    }
    static Metadata checkpointMetadata(Metadata command) {
        return new Metadata(UUID.randomUUID().toString(), command.correlationId(), command.commandId(), command.rationale());
    }
}
