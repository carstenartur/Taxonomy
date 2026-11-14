package com.taxonomy.composition.analysis;

import com.taxonomy.catalog.definition.RetainedCatalogueDefinitions;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisResponsibilityLookupTest {
    private static final RepositoryContext PROJECT = RepositoryContext.workspace("project", "w", "main", "reader");
    private static final String SHA = "a".repeat(40);
    private static final String MODEL = """
            meta {
              catalogueRevision: "archived";
            }
            organization unit {
              title: "Operations";
            }
            element BR-1002 type BusinessRole {
              title: "Staff";
              taxonomy: "BR";
              x-responsible-organization-id: "unit";
            }
            """;
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final ArchitectureEditorService editor = mock(ArchitectureEditorService.class);
    private final RetainedCatalogueDefinitions catalogues = mock(RetainedCatalogueDefinitions.class);
    private final SelectedArchitectureAdmissionModel service = new SelectedArchitectureAdmissionModel(resolver, editor, catalogues);

    @Test void onlyExactScopedBytesAndCatalogueCanColorAnUnmodeledDescendant() throws Exception {
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(PROJECT);
        when(editor.read(PROJECT, SHA, null)).thenReturn(new ArchitectureEditorService.Document(
                Context.version(PROJECT, SHA), MODEL, "HISTORICAL", List.of(), List.of(), 0, null, "GIT_CHECKPOINT"));
        when(catalogues.analysisParents(any(), eq("archived"))).thenReturn(Map.of("BR-1002","BR", "BR-1081","BR-1002"));
        var rows = service.responsibilities("project", "w", "main", SHA, null, StableIdentityHash.sha256(MODEL), "archived", false);
        assertEquals(new Assignment("unit", "Operations", "BR-1002"), rows.get("BR-1081"));
        assertThrows(ResponseStatusException.class, () -> service.responsibilities("project","w","main",SHA,null,
                "f".repeat(64),"archived",false));
        assertThrows(ResponseStatusException.class, () -> service.responsibilities("project","w","main",SHA,null,
                StableIdentityHash.sha256(MODEL),"different",false));
        verify(editor, never()).read(PROJECT, null, null);
    }

    @Test void aSavedUnpublishedRevisionUsesItsOwnBytesEvenAfterTheWorkingCopyAdvances() throws Exception {
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(PROJECT);
        when(editor.read(PROJECT, null, 7L)).thenReturn(new ArchitectureEditorService.Document(
                Context.of(PROJECT, "b".repeat(40), 7), MODEL, "HISTORICAL", List.of(), List.of(), 19, null, "WORKSPACE_REVISION"));
        when(catalogues.analysisParents(any(), eq("archived"))).thenReturn(Map.of("BR-1081","BR-1002"));
        var rows = service.responsibilities("project", "w", "main", SHA, 7L, StableIdentityHash.sha256(MODEL), "archived", false);
        assertEquals("Operations", rows.get("BR-1081").name());
        verify(editor, never()).read(PROJECT, SHA, null);
        verify(editor, never()).read(PROJECT, null, null);
    }

    @Test void onlyAnExplicitLiveLookupCanSelectTodaysArchitecture() throws Exception {
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(PROJECT);
        when(editor.read(PROJECT, null, null)).thenReturn(new ArchitectureEditorService.Document(
                Context.of(PROJECT, SHA, 4), MODEL, "READY", List.of(), List.of(), 4, null, "WORKSPACE_REVISION"));
        assertTrue(service.resolve(null).isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.responsibilities("project", "w", "main",
                null, null, null, null, false));
        verifyNoInteractions(editor);
        when(catalogues.analysisParents(any(), eq("archived"))).thenReturn(Map.of());
        assertEquals("Operations", service.responsibilities("project", "w", "main", null, null, null, null, true)
                .get("BR-1002").name());
    }

    @Test void anotherWorkspaceAndMissingHistoricalCoordinatesNeverReadTheCurrentTree() throws Exception {
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(PROJECT);
        assertThrows(ResponseStatusException.class, () -> service.responsibilities("project","foreign","main",SHA,null,
                StableIdentityHash.sha256(MODEL),"archived",false));
        assertThrows(ResponseStatusException.class, () -> service.responsibilities("project","w","main",null,null,
                StableIdentityHash.sha256(MODEL),"archived",false));
        verifyNoInteractions(editor);
        when(editor.read(PROJECT,SHA,null)).thenThrow(new CommandProblem("NOT_FOUND","commit","Missing checkpoint",List.of()));
        assertThrows(ResponseStatusException.class, () -> service.responsibilities("project","w","main",SHA,null,
                StableIdentityHash.sha256(MODEL),"archived",false));
        verify(editor, never()).read(PROJECT,null,null);
    }
}
