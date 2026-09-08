package com.taxonomy.interop;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.persistence.*;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.orm.jpa.JpaTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Separate application JVMs cross every durable recovery boundary, including Git completion before integration acknowledgement. */
class IntegrationRestartTest {
    @TempDir Path directory;
    @Test void discoveryPreviewApplyCheckpointAndFrozenDeliverySurviveCompleteProcessRestarts() throws Exception {
        for (String phase : List.of("fetch", "preview", "apply", "git", "complete", "verify")) {
            Path log = directory.resolve(phase + ".log");
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), RecoveryApplication.class.getName(), directory.resolve("database").toString(), phase)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS); if (!finished) process.destroyForcibly();
            assertTrue(finished, "Recovery process did not finish: " + phase);
            assertEquals(0, process.exitValue(), Files.readString(log));
        }
    }
    public static final class RecoveryApplication {
        private static final RepositoryContext CONTEXT = RepositoryContext.workspace("repo-restart", "workspace-restart", "draft", "alice");
        private static final UUID CONNECTION = UUID.fromString("1502e67c-2941-4991-92ef-000000000001"), OPERATION = UUID.fromString("1502e67c-2941-4991-92ef-000000000002"),
                CHECKPOINT = UUID.fromString("1502e67c-2941-4991-92ef-000000000003"), EXPORT = UUID.fromString("1502e67c-2941-4991-92ef-000000000004");
        private static final Class<?>[] ENTITIES = { IntegrationConnectionEntity.class, IntegrationOperationEntity.class, ExternalIdentityMappingEntity.class, IntegrationCheckpointEntity.class, IntegrationEventEntity.class };
        public static void main(String[] args) throws Exception {
            try (var fixture = new EditorPersistenceFixture("jdbc:hsqldb:file:" + args[0] + ";shutdown=true", ENTITIES)) {
                var store = new IntegrationStore(fixture.factory, new JpaTransactionManager(fixture.factory), new IntegrationJson(JsonMapper.builder().build()));
                var context = fixture.service.read(CONTEXT, null).context();
                var state = new InternalState(context.repositoryId(), context.workspaceScopeKey(), context.branch(), context.commit(), context.revision(), null, "none");
                var external = new ExternalScope("Reference provider", "model-1", "baseline-1");
                var authority = new IntegrationContext(CONNECTION, AuthorityMode.BIDIRECTIONAL, external, state, "alice", "archimate-3.1", "1");
                var document = new ExchangeDocument("archimate-3.1", "1", "v1", true, "", List.of(), List.of(), List.of(), Map.of(), List.of());
                switch (args[1]) {
                    case "fetch" -> {
                        store.create(CONTEXT, CONNECTION, "USER:alice", "Restart reference", "archimate-3.1", "1", AuthorityMode.BIDIRECTIONAL, external, null, null);
                        store.locked(CONTEXT, CONNECTION, s -> { s.preview(OPERATION, authority, "INBOUND", "fingerprint", document, List.of()); s.fetching(OPERATION); return null; });
                    }
                    case "preview" -> {
                        check(store.operation(CONTEXT, CONNECTION, OPERATION).status() == OperationStatus.FETCH_PENDING);
                        store.locked(CONTEXT, CONNECTION, s -> { s.fetched(OPERATION, document, List.of()); return null; });
                    }
                    case "apply" -> {
                        check(store.operation(CONTEXT, CONNECTION, OPERATION).status() == OperationStatus.PREVIEWED);
                        store.locked(CONTEXT, CONNECTION, s -> {
                            s.beginReview(review(OPERATION));
                            try {
                                Context next = fixture.service.acceptIntegration(CONTEXT, context, metadata(OPERATION), "review",
                                        List.of(new CreateArchitectureElement("arch-restart-import", "System", Map.of("title", "Recovered import"))), null, metadata(CHECKPOINT));
                                var artifact = new Artifact("external-1", ArtifactKind.ELEMENT, "ApplicationComponent", "Recovered import", "", Map.of(), Map.of("canonicalType", "System"));
                                s.mapping(OPERATION, "ELEMENT:external-1", "arch-restart-import", null, "v1", artifact, artifact, false);
                                s.applied(OPERATION, new InternalState(next.repositoryId(), next.workspaceScopeKey(), next.branch(), next.commit(), next.revision(), null, "none"), document, true);
                            } catch (Exception e) { throw new AssertionError(e); }
                            return null;
                        });
                        check(fixture.repositories.resolveRepository(CONTEXT).getHeadCommit("draft") == null);
                    }
                    case "git" -> {
                        check(store.operation(CONTEXT, CONNECTION, OPERATION).status() == OperationStatus.CHECKPOINT_PENDING);
                        check(fixture.journal.read(CONTEXT).operations().size() == 1);
                        fixture.service.checkpoint(CONTEXT, new CreateCheckpointCommand(Context.of(CONTEXT, null, 1), metadata(CHECKPOINT)));
                        // The application stops before acknowledging the integration checkpoint.
                    }
                    case "complete" -> {
                        var checkpoint = fixture.service.checkpoint(CONTEXT, new CreateCheckpointCommand(Context.of(CONTEXT, null, 1), metadata(CHECKPOINT)));
                        check(checkpoint.replayed());
                        store.locked(CONTEXT, CONNECTION, s -> {
                            s.complete(OPERATION, state, "v1", "verified", true);
                            s.preview(EXPORT, authority, "OUTBOUND", "fingerprint", document, List.of()); s.beginReview(review(EXPORT)); s.applied(EXPORT, state, document, false);
                            s.file(EXPORT, new ExchangeFile("application/xml", "reviewed.xml", "durable-file".getBytes(java.nio.charset.StandardCharsets.UTF_8), List.of()));
                            s.complete(EXPORT, state, "v1", "exported", false); return null;
                        });
                    }
                    case "verify" -> {
                        check(store.operation(CONTEXT, CONNECTION, OPERATION).status() == OperationStatus.COMPLETED);
                        check(store.identities(CONTEXT, CONNECTION).getFirst().businessIdentity().equals("arch-restart-import"));
                        check(fixture.journal.read(CONTEXT).operations().size() == 1);
                        check(fixture.repositories.resolveRepository(CONTEXT).getCommitCount("draft") == 1);
                        check(new String(store.operation(CONTEXT, CONNECTION, EXPORT).resultFile().content(), java.nio.charset.StandardCharsets.UTF_8).equals("durable-file"));
                        check(store.checkpoint(CONTEXT, CONNECTION).operationId().equals(OPERATION));
                    }
                    default -> throw new IllegalArgumentException(args[1]);
                }
            }
        }
        private static Metadata metadata(UUID id) { return new Metadata(id.toString(), id.toString(), id.toString(), "Reviewed recovery"); }
        private static ReviewedChangeSet review(UUID id) { return new ReviewedChangeSet(id, "fingerprint", Map.of(), "Reviewed recovery"); }
        private static void check(boolean condition) { if (!condition) throw new AssertionError("Integration recovery contract failed"); }
    }
}
