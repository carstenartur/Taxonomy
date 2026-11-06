package com.taxonomy.editor;

import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Each phase is a different application JVM, using the same durable journal/Git database. */
class ArchitectureEditorRestartTest {
    @TempDir Path directory;
    @Test void operationRetryUndoRedoHistoryAndCanonicalStateSurviveCompleteProcessRestarts() throws Exception {
        phases("accept", "undo", "redo", "verify");
    }

    @Test void sharedOptInAndBothActorsOperationsSurviveIndependentApplicationProcesses() throws Exception {
        phases("shared-accept", "shared-contribute", "shared-undo", "shared-verify");
    }

    private void phases(String... phases) throws Exception {
        for (String phase : phases) {
            Path output = directory.resolve(phase + ".log");
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    RecoveryApplication.class.getName(), directory.resolve("database").toString(), phase)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            assertThat(finished).as("Recovery JVM timeout: %s", phase).isTrue();
            assertThat(process.exitValue()).as("%s: %s", phase, Files.readString(output)).isZero();
        }
    }

    public static final class RecoveryApplication {
        public static void main(String[] args) throws Exception {
            RepositoryContext scope = RepositoryContext.workspace("restart-repository", "restart-workspace", "draft", "alice");
            if (args[1].startsWith("shared-")) scope = RepositoryContext.collaborativeWorkspace("restart-repository", "restart-workspace", "draft", "alice");
            String originalId = "e15f597d-a235-4cf6-aac3-ef42c7a66201";
            String undoId = "e15f597d-a235-4cf6-aac3-ef42c7a66202";
            try (var fixture = new EditorPersistenceFixture("jdbc:hsqldb:file:" + args[0] + ";shutdown=true", com.taxonomy.workspace.model.UserWorkspace.class)) {
                var editor = fixture.service;
                var command = new Command(Context.of(scope, null, 0), metadata(originalId),
                        new SemanticCommand(new CreateArchitectureElement("arch-persisted", "System", Map.of("title", "Durable"))));
                switch (args[1]) {
                    case "shared-accept" -> {
                        try (var session = fixture.factory.openSession()) {
                            var tx = session.beginTransaction(); var workspace = new com.taxonomy.workspace.model.UserWorkspace();
                            workspace.setWorkspaceId(scope.workspaceId()); workspace.setUsername("alice");
                            workspace.setSourceRepositoryId(scope.repositoryId()); workspace.setCurrentBranch("draft");
                            workspace.setCreatedAt(java.time.Instant.now()); workspace.setCollaborative(true);
                            session.persist(workspace); tx.commit();
                        }
                        editor.execute(scope, command);
                    }
                    case "shared-contribute" -> {
                        var bob = RepositoryContext.collaborativeWorkspace(scope.repositoryId(), scope.workspaceId(), scope.branch(), "bob");
                        editor.execute(bob, new Command(editor.read(bob, null).context(), metadata(UUID.randomUUID().toString()),
                                new SemanticCommand(new CreateArchitectureElement("arch-bob", "Component", Map.of("title", "Bob durable")))));
                        check(editor.read(scope, null).history().size() == 2);
                        check(editor.read(scope, null).historyTargets().undo().actor().equals("alice"));
                        check(editor.read(bob, null).historyTargets().undo().actor().equals("bob"));
                    }
                    case "shared-undo" -> {
                        editor.execute(scope, new Command(editor.read(scope, null).context(), metadata(undoId),
                                new UndoArchitectureCommand(UUID.fromString(originalId))));
                        check(editor.read(scope, null).dsl().contains("Bob durable"));
                        check(!editor.read(scope, null).dsl().contains("element arch-persisted"));
                    }
                    case "shared-verify" -> {
                        try (var session = fixture.factory.openSession()) {
                            check(session.createQuery("from UserWorkspace", com.taxonomy.workspace.model.UserWorkspace.class).getSingleResult().isCollaborative());
                        }
                        editor.execute(scope, new Command(editor.read(scope, null).context(), metadata(UUID.randomUUID().toString()),
                                new RedoArchitectureCommand(UUID.fromString(undoId))));
                        var document = editor.read(scope, null);
                        check(document.context().revision() == 4); check(document.history().size() == 4);
                        check(document.dsl().contains("Durable") && document.dsl().contains("Bob durable"));
                        check(fixture.repositories.resolveRepository(scope).getHeadCommit("draft") == null);
                    }
                    case "accept" -> { editor.execute(scope, command); check(fixture.journal.read(scope).operations().size() == 1); }
                    case "undo" -> {
                        check(editor.execute(scope, command).replayed());
                        check(editor.read(scope, null).dsl().contains("Durable"));
                        editor.execute(scope, new Command(Context.of(scope, null, 1), metadata(undoId), new UndoArchitectureCommand(UUID.fromString(originalId))));
                        check(editor.read(scope, null).dsl().isEmpty());
                    }
                    case "redo" -> {
                        check(editor.read(scope, null).history().getFirst().targetOperationId().equals(originalId));
                        var redo = new Command(Context.of(scope, null, 2), metadata(UUID.randomUUID().toString()), new RedoArchitectureCommand(UUID.fromString(undoId)));
                        editor.preview(scope, redo); editor.execute(scope, redo);
                    }
                    case "verify" -> {
                        var document = editor.read(scope, null);
                        check(document.dsl().contains("Durable")); check(document.history().size() == 3);
                        check(document.context().revision() == 3);
                        check(document.history().getFirst().kind().equals("REDO"));
                        check(document.history().getFirst().targetOperationId().equals(undoId));
                        check(fixture.repositories.resolveRepository(scope).getHeadCommit("draft") == null);
                    }
                    default -> throw new IllegalArgumentException(args[1]);
                }
            }
        }
        private static Metadata metadata(String id) { return new Metadata(id, id, id, "Restart acceptance"); }
        private static void check(boolean condition) { if (!condition) throw new AssertionError("Durable recovery contract failed"); }
    }
}
