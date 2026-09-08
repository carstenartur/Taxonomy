package com.taxonomy.dsl.storage;

import com.taxonomy.editor.ArchitectureCommandPort;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionResult;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.CoreEntities;
import jakarta.persistence.EntityManagerFactory;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ReflogEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration coverage for Taxonomy consuming jgit-storage-hibernate-core. */
@SpringBootTest
@TestPropertySource(properties = {
        "gemini.api.key=",
        "openai.api.key=",
        "deepseek.api.key=",
        "qwen.api.key=",
        "llama.api.key=",
        "mistral.api.key=",
        "embedding.enabled=false"
})
class JgitStorageHibernateIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private HibernateRepositoryFactory storageFactory;

    @Autowired
    private com.taxonomy.editor.persistence.EditorJournal journal;

    @Test
    void registersAllCoreStorageEntitiesInTheApplicationPersistenceUnit() {
        Set<Class<?>> managedTypes = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(entityType -> entityType.getJavaType())
                .collect(Collectors.toSet());

        assertThat(managedTypes).containsAll(CoreEntities.annotatedClasses());
    }

    @Test
    void persistsDslAndQueryableReflogAcrossRepositoryHandleReopen() throws Exception {
        RepositoryName repositoryName = uniqueRepositoryName("taxonomy-it-reopen-");
        String commitId;
        try {
            try (DslGitRepository repository =
                         new DslGitRepository(storageFactory, repositoryName.value())) {
                commitId = repository.commitDsl(
                        "draft", sampleDsl("first"), "integration@test", "initial version");

                ReflogEntry reflog = repository.getGitRepository()
                        .getReflogReader(Constants.R_HEADS + "draft")
                        .getLastEntry();
                assertThat(reflog).isNotNull();
                assertThat(reflog.getNewId().name()).isEqualTo(commitId);
                assertThat(reflog.getComment()).contains("commit: initial version");
            }

            try (DslGitRepository reopened =
                         new DslGitRepository(storageFactory, repositoryName.value())) {
                assertThat(reopened.getHeadCommit("draft")).isEqualTo(commitId);
                assertThat(reopened.getDslAtHead("draft")).isEqualTo(sampleDsl("first"));
                assertThat(reopened.getDslHistory("draft")).hasSize(1);
            }
        } finally {
            storageFactory.deleteRepository(repositoryName);
        }
    }

    @Test
    void repositoryDeletionIsScopedToOneLogicalRepository() throws Exception {
        RepositoryName firstName = uniqueRepositoryName("taxonomy-it-first-");
        RepositoryName secondName = uniqueRepositoryName("taxonomy-it-second-");
        try {
            try (DslGitRepository first = new DslGitRepository(storageFactory, firstName.value());
                 DslGitRepository second = new DslGitRepository(storageFactory, secondName.value())) {
                first.commitDsl("draft", sampleDsl("first"), "integration@test", "first");
                second.commitDsl("draft", sampleDsl("second"), "integration@test", "second");
            }

            RepositoryDeletionResult deletion = storageFactory.deleteRepository(firstName);
            assertThat(deletion.packRows()).isPositive();
            assertThat(deletion.reflogRows()).isPositive();

            try (DslGitRepository deleted = new DslGitRepository(storageFactory, firstName.value());
                 DslGitRepository survivor = new DslGitRepository(storageFactory, secondName.value())) {
                assertThat(deleted.getBranchNames()).isEmpty();
                assertThat(survivor.getDslAtHead("draft")).isEqualTo(sampleDsl("second"));
            }
        } finally {
            storageFactory.deleteRepository(firstName);
            storageFactory.deleteRepository(secondName);
        }
    }

    @Test
    void semanticEditorHistoryAndInverseSurviveAllRepositoryHandlesBeingClosed() throws Exception {
        String workspaceId = "editor-reopen-" + UUID.randomUUID();
        var context = RepositoryContext.workspace("editor-source", workspaceId, "draft", "alice");
        var rebuild = org.mockito.Mockito.mock(RelationBranchProjectionRebuildService.class);
        var readiness = org.mockito.Mockito.mock(RelationBranchProjectionReadinessService.class);
        String id = UUID.randomUUID().toString();
        var metadata = new ArchitectureCommandPort.Metadata(id, id, id, "Persisted architecture decision");
        var command = new ArchitectureCommandPort.Command(
                ArchitectureCommandPort.Context.of(context, null), metadata,
                new ArchitectureCommandPort.SemanticCommand(
                        new ArchitectureCommand.CreateArchitectureElement("arch-persisted", "System", java.util.Map.of("title", "Durable"))));
        String accepted;
        String undo;
        try {
            try (var first = new DslGitRepositoryFactory(storageFactory)) {
                var editor = new ArchitectureEditorService(first, journal, new com.taxonomy.editor.ArchitectureCheckpointWriter());
                accepted = editor.execute(context, command).operationId();
            }
            try (var reopened = new DslGitRepositoryFactory(storageFactory)) {
                var editor = new ArchitectureEditorService(reopened, journal, new com.taxonomy.editor.ArchitectureCheckpointWriter());
                assertThat(editor.execute(context, command).replayed()).isTrue();
                assertThat(editor.read(context, null).history()).hasSize(1);
                String undoId = UUID.randomUUID().toString();
                undo = editor.execute(context, new ArchitectureCommandPort.Command(
                        ArchitectureCommandPort.Context.of(context, null, 1),
                        new ArchitectureCommandPort.Metadata(undoId, id, id, "Undo after reopening"),
                        new ArchitectureCommandPort.UndoArchitectureCommand(UUID.fromString(accepted)))).operationId();
                assertThat(editor.read(context, null).dsl()).isEmpty();
            }
            try (var reopened = new DslGitRepositoryFactory(storageFactory)) {
                var editor = new ArchitectureEditorService(reopened, journal, new com.taxonomy.editor.ArchitectureCheckpointWriter());
                assertThat(editor.read(context, null).history()).extracting(ArchitectureEditorService.HistoryEntry::kind)
                        .containsExactly("UNDO", "CreateArchitectureElement");
                String redoId = UUID.randomUUID().toString();
                editor.execute(context, new ArchitectureCommandPort.Command(
                        ArchitectureCommandPort.Context.of(context, null, 2),
                        new ArchitectureCommandPort.Metadata(redoId, id, id, "Redo after reopening"),
                        new ArchitectureCommandPort.RedoArchitectureCommand(UUID.fromString(undo))));
                assertThat(editor.read(context, null).dsl()).contains("arch-persisted", "Durable");
            }
        } finally {
            try (var cleanup = new DslGitRepositoryFactory(storageFactory)) {
                cleanup.deleteWorkspaceRepository(workspaceId);
            }
        }
    }


    private static RepositoryName uniqueRepositoryName(String prefix) {
        return new RepositoryName(prefix + UUID.randomUUID());
    }

    private static String sampleDsl(String title) {
        return """
                meta {
                  language: "taxdsl";
                  version: "2.0";
                  namespace: "integration";
                }

                element CP-1023 type Capability {
                  title: "%s";
                }
                """.formatted(title);
    }
}
