package com.taxonomy.editor;

import com.taxonomy.TaxonomyApplication;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Each invocation boots and terminates the whole application in a separate JVM and opens the same database. */
public final class ArchitectureApplicationRestartDriver {
    private static final RepositoryContext SCOPE = RepositoryContext.workspace("restart-source", "restart-workspace", "draft", "alice");
    private static final String CREATE = "00000000-0000-0000-0000-000000000001";
    private static final String UPDATE = "00000000-0000-0000-0000-000000000002";
    private static final String UNDO = "00000000-0000-0000-0000-000000000003";
    private static final String REDO = "00000000-0000-0000-0000-000000000004";

    public static void main(String[] args) throws Exception {
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + args[0],
                "--spring.datasource.username=SA", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--embedding.enabled=false", "--taxonomy.init.async=false",
                "--gemini.api.key=", "--openai.api.key=", "--deepseek.api.key=", "--qwen.api.key=", "--llama.api.key=", "--mistral.api.key=")) {
            var editor = app.getBean(ArchitectureEditorService.class);
            var journal = app.getBean(EditorJournal.class);
            var versions = app.getBean(DslGitRepositoryFactory.class).resolveRepository(SCOPE);
            var initial = new Command(Context.of(SCOPE, null, 0), metadata(CREATE),
                    new SemanticCommand(new CreateArchitectureElement("arch-restart", "System", Map.of("title", "Original"))));
            if ("write".equals(args[1])) {
                var created = editor.execute(SCOPE, initial);
                editor.execute(SCOPE, new Command(created.context(), metadata(UPDATE),
                        new SemanticCommand(new UpdateArchitectureElement("arch-restart", "System", Map.of("title", "Recovered")))));
                assertThat(editor.read(SCOPE, null).context().revision()).isEqualTo(2L);
            } else {
                assertThat(editor.execute(SCOPE, initial).replayed()).isTrue();
                var current = editor.read(SCOPE, null);
                if ("undo".equals(args[1])) {
                    assertThat(current.context().revision()).isEqualTo(2L);
                    assertThat(current.dsl()).contains("Recovered");
                    editor.execute(SCOPE, new Command(current.context(), metadata(UNDO), new UndoArchitectureCommand(UUID.fromString(UPDATE))));
                    assertThat(editor.read(SCOPE, null).dsl()).contains("Original");
                } else {
                    assertThat(current.context().revision()).isEqualTo(3L);
                    assertThat(current.dsl()).contains("Original");
                    editor.execute(SCOPE, new Command(current.context(), metadata(REDO), new RedoArchitectureCommand(UUID.fromString(UNDO))));
                    var recovered = editor.read(SCOPE, null);
                    assertThat(recovered.dsl()).contains("Recovered");
                    assertThat(recovered.history()).extracting(ArchitectureEditorService.HistoryEntry::operationId)
                            .containsExactly(REDO, UNDO, UPDATE, CREATE);
                    assertThat(versions.getHeadCommit("draft")).isNull();
                    assertThat(editor.rebuild(SCOPE, recovered.context())).isEqualTo("READY");
                    var projection = app.getBean(ArchitectureEditorProjection.class).project(recovered, true);
                    assertThat(projection.searchIndex()).extracting(ArchitectureEditorProjection.SearchEntry::text)
                            .anyMatch(text -> text.contains("recovered"));
                    var checkpoint = editor.checkpoint(SCOPE,
                            new CreateCheckpointCommand(recovered.context(), metadata(UUID.randomUUID().toString())));
                    assertThat(versions.getDslHistory("draft")).hasSize(1);
                    assertThat(versions.getDslAtCommit(checkpoint.commitId())).isEqualTo(recovered.dsl());
                    assertThat(journal.read(SCOPE).operations()).hasSize(4);
                }
            }
            if (!"redo".equals(args[1])) assertThat(versions.getHeadCommit("draft")).isNull();
        }
        System.out.println("ARCHITECTURE_RESTART_OK " + args[1]);
    }

    private static Metadata metadata(String id) { return new Metadata(id, id, id, "Durable restart proof"); }
}
