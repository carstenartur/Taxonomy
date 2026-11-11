package com.taxonomy.composition.dsl.controller;

import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.provenance.CatalogueSourceBytes;
import com.taxonomy.dsl.model.ProjectCatalogue;
import com.taxonomy.editor.ArchitectureCommandPort;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectDslControllerTest {
    private static final UUID A = UUID.fromString("016cd9ec-0cd7-49b2-a750-6accccc35fad");
    private static final UUID B = UUID.fromString("545ad93f-2e03-408c-a6bc-84cd88f962a6");
    private static final String COMMIT = "b".repeat(40);
    private final RepositoryContext context = RepositoryContext.workspace("r", "w", "draft", "alice");
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final ArchitectureEditorService editor = mock(ArchitectureEditorService.class);
    private final CatalogueSourceJournal journal = mock(CatalogueSourceJournal.class);
    private final ProjectDslController api = new ProjectDslController(new com.taxonomy.composition.dsl.service.ProjectDslSource(resolver, editor), journal);

    private void selected(String dsl) throws Exception {
        var document = mock(ArchitectureEditorService.Document.class);
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(context);
        when(editor.read(context, COMMIT, null)).thenReturn(document);
        when(document.dsl()).thenReturn(dsl);
        when(document.context()).thenReturn(ArchitectureCommandPort.Context.of(context, COMMIT, 3));
        when(document.source()).thenReturn("GIT_CHECKPOINT");
    }

    @Test void journaledLegacyImportRetainsItsIdentityWithoutClaimingAnArchivedWorkbook() throws Exception {
        var missing = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_RETAINED, null, 0);
        var journaled = new CatalogueSourceJournal.Snapshot(A.toString(), java.time.Instant.EPOCH, missing, null, null);
        when(journal.findRevision(A.toString())).thenReturn(Optional.of(journaled));
        String original = ProjectCatalogue.initial("project", A);
        selected(original);
        var response = api.read("r", "draft", COMMIT, null);
        assertEquals(original, response.getBody());
        assertEquals("UNRESOLVED", response.getHeaders().getFirst("X-Taxonomy-Catalogue-State"));
        assertNull(response.getHeaders().getFirst("X-Taxonomy-Catalogue-Revision"));
        assertEquals(journaled, api.catalogues("r", "draft", COMMIT, null).getBody().basis());
        assertThrows(ResponseStatusException.class, () -> api.archivedSource("r", "draft", COMMIT, null, "workbook", false));
        verify(journal, never()).current();
    }

    @Test void historicalSelectionAndExplicitComparisonReadTheirOwnArchivedBytes() throws Exception {
        var oldBytes = CatalogueSourceBytes.read(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        var newBytes = CatalogueSourceBytes.read(new ByteArrayInputStream(new byte[]{4, 5, 6}));
        var oldInput = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.APPLIED, oldBytes.sha256(), 3);
        var newInput = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.APPLIED, newBytes.sha256(), 3);
        var old = new CatalogueSourceJournal.Snapshot(A.toString(), java.time.Instant.EPOCH, oldInput, null, null);
        var current = new CatalogueSourceJournal.Snapshot(B.toString(), java.time.Instant.EPOCH, newInput, null, null);
        when(journal.findRevision(A.toString())).thenReturn(Optional.of(old));
        when(journal.findRevision(B.toString())).thenReturn(Optional.of(current));
        when(journal.retainedWorkbook(oldInput)).thenReturn(oldBytes);
        when(journal.retainedWorkbook(newInput)).thenReturn(newBytes);
        when(journal.current()).thenReturn(current);
        String original = ProjectCatalogue.initial("project", A) + "# keep verbatim\nelement STANDALONE type System {\n  x-custom: yes;\n}\n";
        String comparison = api.compareCurrent(original).getBody();
        selected(comparison);
        var read = api.read("r", "draft", COMMIT, null);
        assertEquals(comparison, read.getBody());
        assertEquals(A.toString(), read.getHeaders().getFirst("X-Taxonomy-Catalogue-Revision"));
        assertEquals(old, api.catalogues("r", "draft", COMMIT, null).getBody().basis());
        assertArrayEquals(new byte[]{1,2,3}, api.archivedSource("r","draft",COMMIT,null,"workbook",false).getBody());
        assertArrayEquals(new byte[]{4,5,6}, api.archivedSource("r","draft",COMMIT,null,"workbook",true).getBody());
    }

    @Test void allProjectSourceRecordsAndExtensionsSurviveWithoutAnIncidentRelation() throws Exception {
        String project = """
                package LOCAL-PACKAGE {
                  title: "Community library";
                }
                element READING-ROOM type System {
                  package: LOCAL-PACKAGE;
                  title: "Lesesaal";
                  x-local-source: "builder";
                }
                requirement R1 {
                  text: "Provide a reading room.";
                }
                mapping R1 READING-ROOM {
                  confidence: 0.9;
                }
                view FLOORPLAN {
                  include: READING-ROOM;
                }
                evidence E1 {
                  description: "Reviewed site plan";
                }
                source USER-BRIEF {
                  type: "reference";
                }
                sourceVersion BRIEF-1 {
                  source: USER-BRIEF;
                  versionLabel: "1";
                }
                customRecord USER-EXTENSION {
                  x-review: "accepted";
                }
                """;
        selected(project);
        assertEquals(project, api.read("r", "draft", COMMIT, null).getBody());
    }

    @Test void unreadablePinDoesNotFallBackToTodayAndTheOriginalTextIsUntouched() throws Exception {
        String legacy = "# größe Ελλάδα 📚 untouched\nextension SOME_EXTENSION {\n  x-opaque: yes;\n}\n";
        selected(legacy);
        var read = api.read("r", "draft", COMMIT, null);
        assertEquals(legacy, read.getBody());
        assertEquals(java.nio.charset.StandardCharsets.UTF_8, read.getHeaders().getContentType().getCharset());
        assertEquals("UNRESOLVED", read.getHeaders().getFirst("X-Taxonomy-Catalogue-State"));
        assertThrows(ResponseStatusException.class, () -> api.archivedSource("r","draft",COMMIT,null,"workbook",false));
        verify(journal, never()).current();
        assertThrows(ResponseStatusException.class, () -> api.read("different-project","draft",COMMIT,null));
    }
}
