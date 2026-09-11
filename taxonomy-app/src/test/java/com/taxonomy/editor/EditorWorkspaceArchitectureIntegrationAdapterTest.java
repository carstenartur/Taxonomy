package com.taxonomy.editor;

import com.taxonomy.editor.ArchitectureCommandPort.CreateCheckpointCommand;
import com.taxonomy.editor.persistence.EditorJournal.RevisionConflict;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.CommandMetadata;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.State;
import com.taxonomy.workspace.service.WorkspaceRevisionConflict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EditorWorkspaceArchitectureIntegrationAdapterTest {

    private static final long EXPECTED_REVISION = 7L;
    private static final long ACTUAL_REVISION = 9L;

    private final ArchitectureEditorService editor = mock(ArchitectureEditorService.class);
    private final EditorWorkspaceArchitectureIntegrationAdapter adapter =
            new EditorWorkspaceArchitectureIntegrationAdapter(editor);
    private final RepositoryContext context =
            RepositoryContext.workspace("repo", "workspace", "main", "alice");

    @Test
    void readCurrentWorkspaceStateMapsEditorDocument() throws Exception {
        String commit = "a".repeat(40);
        var editorContext = ArchitectureCommandPort.Context.of(context, commit, EXPECTED_REVISION);
        var editorDocument = new ArchitectureEditorService.Document(
                editorContext, "architecture {}", "READY", List.of(), List.of(),
                EXPECTED_REVISION, null, "WORKSPACE_REVISION");
        when(editor.read(context, null)).thenReturn(editorDocument);

        var document = adapter.read(context, null);

        assertEquals(editorContext.workspaceScopeKey(), document.state().workspaceScopeKey());
        assertEquals(commit, document.state().commitId());
        assertEquals(EXPECTED_REVISION, document.state().semanticRevision());
        assertEquals("architecture {}", document.dsl());
        verify(editor).read(context, null);
    }

    @Test
    void readExactCommitMapsHistoricalEditorDocument() throws Exception {
        String commit = "b".repeat(40);
        var editorContext = ArchitectureCommandPort.Context.version(context, commit);
        var editorDocument = new ArchitectureEditorService.Document(
                editorContext, "historical architecture {}", "HISTORICAL", List.of(), List.of(),
                0, null, "GIT_CHECKPOINT");
        when(editor.read(context, commit)).thenReturn(editorDocument);

        var document = adapter.read(context, commit);

        assertEquals(editorContext.workspaceScopeKey(), document.state().workspaceScopeKey());
        assertEquals(commit, document.state().commitId());
        assertEquals(0, document.state().semanticRevision());
        assertEquals("historical architecture {}", document.dsl());
        verify(editor).read(context, commit);
    }

    @Test
    void lockedTranslatesEditorRevisionConflict() throws Exception {
        doThrow(new RevisionConflict(EXPECTED_REVISION, ACTUAL_REVISION))
                .when(editor).integrationBoundary(eq(context), any());

        assertTranslatedConflict(() -> adapter.locked(context, document -> document.dsl()));
    }

    @Test
    void acceptIntegrationTranslatesEditorRevisionConflict() throws Exception {
        State expected = state(EXPECTED_REVISION);
        doThrow(new RevisionConflict(EXPECTED_REVISION, ACTUAL_REVISION))
                .when(editor).acceptIntegration(eq(context), any(ArchitectureCommandPort.Context.class),
                        any(ArchitectureCommandPort.Metadata.class), anyString(), anyList(), any(),
                        any(ArchitectureCommandPort.Metadata.class));

        assertTranslatedConflict(() -> adapter.acceptIntegration(context, expected, metadata(), "fingerprint",
                List.of(), UnaryOperator.identity(), metadata()));
    }

    @Test
    void checkpointTranslatesEditorRevisionConflict() throws Exception {
        State expected = state(EXPECTED_REVISION);
        doThrow(new RevisionConflict(EXPECTED_REVISION, ACTUAL_REVISION))
                .when(editor).checkpoint(eq(context), any(CreateCheckpointCommand.class));

        assertTranslatedConflict(() -> adapter.checkpoint(context, expected, metadata()));
    }

    private State state(long revision) {
        var editorContext = ArchitectureCommandPort.Context.of(context, null, revision);
        return new State(editorContext.workspaceScopeKey(), editorContext.commit(), editorContext.revision());
    }

    private static CommandMetadata metadata() {
        UUID id = UUID.randomUUID();
        return new CommandMetadata(id, id, id, "test revision conflict translation");
    }

    private static void assertTranslatedConflict(Executable action) {
        WorkspaceRevisionConflict conflict = assertThrows(WorkspaceRevisionConflict.class, action);
        assertEquals(EXPECTED_REVISION, conflict.expected());
        assertEquals(ACTUAL_REVISION, conflict.actual());
        assertEquals("Workspace revision moved from 7 to 9", conflict.getMessage());
    }
}
