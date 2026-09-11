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

class EditorWorkspaceArchitectureIntegrationAdapterTest {

    private static final long EXPECTED_REVISION = 7L;
    private static final long ACTUAL_REVISION = 9L;

    private final ArchitectureEditorService editor = mock(ArchitectureEditorService.class);
    private final EditorWorkspaceArchitectureIntegrationAdapter adapter =
            new EditorWorkspaceArchitectureIntegrationAdapter(editor);
    private final RepositoryContext context =
            RepositoryContext.workspace("repo", "workspace", "main", "alice");

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
