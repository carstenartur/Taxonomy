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
        for (String phase : new String[]{"accept", "undo", "redo", "verify"}) {
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
            String originalId = "e15f597d-a235-4cf6-aac3-ef42c7a66201";
            String undoId = "e15f597d-a235-4cf6-aac3-ef42c7a66202";
            try (var fixture = new EditorPersistenceFixture("jdbc:hsqldb:file:" + args[0] + ";shutdown=true")) {
                var editor = fixture.service;
                var command = new Command(Context.of(scope, null, 0), metadata(originalId),
                        new SemanticCommand(new CreateArchitectureElement("arch-persisted", "System", Map.of("title", "Durable"))));
                switch (args[1]) {
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
