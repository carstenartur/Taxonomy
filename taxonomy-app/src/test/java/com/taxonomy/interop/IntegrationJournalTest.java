package com.taxonomy.interop;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.persistence.*;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.orm.jpa.JpaTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationJournalTest {
    @TempDir Path directory;
    private static final RepositoryContext CONTEXT = RepositoryContext.workspace("repo-a", "workspace-a", "draft", "alice");
    private static final Class<?>[] ENTITIES = { IntegrationConnectionEntity.class, IntegrationOperationEntity.class, ExternalIdentityMappingEntity.class, IntegrationCheckpointEntity.class, IntegrationEventEntity.class };
    private final IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());
    private IntegrationStore store(EditorPersistenceFixture fixture) { return new IntegrationStore(fixture.factory, new JpaTransactionManager(fixture.factory), json); }
    private IntegrationStore.Connection connection(IntegrationStore store, UUID id) {
        return store.create(CONTEXT, id, "ORGANIZATION:org-a", "Archi", "archimate-3.1", "1", AuthorityMode.BIDIRECTIONAL, new ExternalScope("Archi", "model-a", null), null, null);
    }
    private InternalState state(EditorPersistenceFixture fixture) throws Exception {
        var context = fixture.service.read(CONTEXT, null).context(); return new InternalState(context.repositoryId(), context.workspaceScopeKey(), context.branch(), context.commit(), context.revision(), null, "portfolio-none");
    }
    private ExchangeDocument document() { return new ExchangeDocument("archimate-3.1", "1", "external-v1", true, "", List.of(), List.of(), List.of(), Map.of(), List.of()); }
    private void preview(IntegrationStore store, UUID connection, UUID operation, InternalState state) {
        store.locked(CONTEXT, connection, session -> session.preview(operation,
                new IntegrationContext(connection, AuthorityMode.BIDIRECTIONAL, new ExternalScope("Archi", "model-a", null), state, "alice", "archimate-3.1", "1"),
                "INBOUND", "fingerprint", document(), List.of()));
    }
    @Test void rollbackCannotLeaveAcceptedModelWithoutIntegrationEvidenceAndCheckpointIntent() throws Exception {
        try (var fixture = new EditorPersistenceFixture("jdbc:hsqldb:mem:interop-" + UUID.randomUUID(), ENTITIES)) {
            IntegrationStore store = store(fixture); UUID connection = UUID.randomUUID(), operation = UUID.randomUUID(); connection(store, connection);
            InternalState before = state(fixture); preview(store, connection, operation, before);
            var review = new ReviewedChangeSet(operation, "fingerprint", Map.of(), "Reviewed import");
            Metadata checkpoint = metadata(UUID.randomUUID());
            assertThrows(IllegalStateException.class, () -> store.locked(CONTEXT, connection, session -> {
                session.beginReview(review);
                try { fixture.service.acceptIntegration(CONTEXT, Context.of(CONTEXT, before.commitId(), before.semanticRevision()), metadata(operation), "review-fingerprint",
                        List.of(new CreateArchitectureElement("arch-imported", "System", Map.of("title", "Imported"))), null, checkpoint); }
                catch (Exception failure) { throw new AssertionError(failure); }
                throw new IllegalStateException("Simulated crash before ORM commit");
            }));
            assertEquals(OperationStatus.PREVIEWED, store.operation(CONTEXT, connection, operation).status());
            assertNull(fixture.journal.read(CONTEXT)); assertFalse(fixture.service.read(CONTEXT, null).dsl().contains("arch-imported"));
            assertNull(fixture.repositories.resolveRepository(CONTEXT).getHeadCommit("draft"));
        }
    }
    @Test void restartPreservesAcceptedStateMappingsAndPendingCheckpointWithoutReapplying() throws Exception {
        String url = "jdbc:hsqldb:file:" + directory.resolve("integration").toAbsolutePath() + ";shutdown=true";
        UUID connection = UUID.randomUUID(), operation = UUID.randomUUID(), checkpointId = UUID.randomUUID();
        Metadata checkpoint = metadata(checkpointId); InternalState initial;
        try (var fixture = new EditorPersistenceFixture(url, ENTITIES)) {
            var store = store(fixture); connection(store, connection); initial = state(fixture); preview(store, connection, operation, initial);
            store.locked(CONTEXT, connection, session -> {
                session.beginReview(new ReviewedChangeSet(operation, "fingerprint", Map.of(), "Reviewed import"));
                try {
                    Context next = fixture.service.acceptIntegration(CONTEXT, Context.of(CONTEXT, null, 0), metadata(operation), "review-fingerprint",
                            List.of(new CreateArchitectureElement("arch-imported", "System", Map.of("title", "Imported"))), null, checkpoint);
                    session.mapping(operation, "ELEMENT:external-1", "arch-imported", null, "external-v1", artifact("Imported"), artifact("Imported"), false);
                    session.applied(operation, new InternalState(next.repositoryId(), next.workspaceScopeKey(), next.branch(), next.commit(), next.revision(), null, "none"), document(), true);
                } catch (Exception failure) { throw new AssertionError(failure); }
                return null;
            });
            assertNull(fixture.repositories.resolveRepository(CONTEXT).getHeadCommit("draft"));
        }
        try (var fixture = new EditorPersistenceFixture(url, ENTITIES)) {
            var store = store(fixture); var pending = store.operation(CONTEXT, connection, operation);
            assertEquals(OperationStatus.CHECKPOINT_PENDING, pending.status()); assertEquals(1, store.identities(CONTEXT, connection).size());
            assertEquals(1, fixture.journal.read(CONTEXT).operations().size()); assertTrue(fixture.service.read(CONTEXT, null).dsl().contains("Imported"));
            var completed = fixture.service.checkpoint(CONTEXT, new CreateCheckpointCommand(Context.of(CONTEXT, null, pending.resultRevision()), checkpoint));
            assertEquals(1, fixture.repositories.resolveRepository(CONTEXT).getCommitCount("draft"));
            assertEquals(completed.commitId(), fixture.service.checkpoint(CONTEXT, new CreateCheckpointCommand(Context.of(CONTEXT, null, 1), checkpoint)).commitId());
            store.locked(CONTEXT, connection, session -> { session.complete(operation, new InternalState(CONTEXT.repositoryId(), "workspace-a", "draft", completed.commitId(), 1, null, "none"), "external-v1", "verified", true); return null; });
            assertEquals(OperationStatus.COMPLETED, store.operation(CONTEXT, connection, operation).status());
            assertEquals(1, fixture.journal.read(CONTEXT).operations().size());
        }
    }
    @Test void connectionLockAllowsOnlyOneReviewOfTheSameBaselineAndTenantLookupIsNonDisclosing() throws Exception {
        try (var fixture = new EditorPersistenceFixture("jdbc:hsqldb:mem:interop-" + UUID.randomUUID(), ENTITIES)) {
            var store = store(fixture); UUID connection = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID(); connection(store, connection);
            InternalState state = state(fixture); preview(store, connection, first, state); preview(store, connection, second, state);
            CyclicBarrier ready = new CyclicBarrier(2);
            try (var executor = Executors.newFixedThreadPool(2)) {
                List<Future<Boolean>> results = new ArrayList<>();
                for (UUID id : List.of(first, second)) results.add(executor.submit(() -> {
                    ready.await();
                    try { store.locked(CONTEXT, connection, session -> { session.beginReview(new ReviewedChangeSet(id, "fingerprint", Map.of(), "Reviewed")); session.applied(id, state, document(), true); return null; }); return true; }
                    catch (IntegrationProblem conflict) { return false; }
                }));
                int accepted = 0; for (var result : results) if (result.get(15, TimeUnit.SECONDS)) accepted++; assertEquals(1, accepted);
            }
            var other = RepositoryContext.workspace("repo-b", "workspace-a", "draft", "alice");
            assertEquals(404, assertThrows(IntegrationProblem.class, () -> store.operation(other, connection, first)).status());
            assertThrows(IntegrationProblem.class, () -> store.locked(CONTEXT, connection, s -> s.preview(first,
                    new IntegrationContext(connection, AuthorityMode.BIDIRECTIONAL, new ExternalScope("Archi", "model-a", null), state, "alice", "archimate-3.1", "1"), "INBOUND", "different-payload", document(), List.of())));
        }
    }
    private static Metadata metadata(UUID id) { return new Metadata(id.toString(), id.toString(), id.toString(), "Reviewed import"); }
    private static Artifact artifact(String title) { return new Artifact("external-1", ArtifactKind.ELEMENT, "ApplicationComponent", title, "", Map.of(), Map.of("canonicalType", "System")); }
}
