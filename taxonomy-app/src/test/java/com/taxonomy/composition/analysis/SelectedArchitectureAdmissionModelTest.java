package com.taxonomy.composition.analysis;

import com.taxonomy.catalog.definition.RetainedCatalogueDefinitions;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dsl.model.ProjectCatalogue;
import com.taxonomy.dsl.planning.ArchitectureBrief;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.editor.ArchitectureEditorService.Document;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.taxonomy.dto.AnalysisModelBinding.State.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SelectedArchitectureAdmissionModelTest {
    private static final UUID A = UUID.fromString("01010101-0101-4101-a101-010101010101");
    private static final UUID B = UUID.fromString("02020202-0202-4202-a202-020202020202");
    private static final String FIRST = "a".repeat(40), SECOND = "b".repeat(40);
    private static final RepositoryContext PROJECT = RepositoryContext.workspace("project", "workspace", "design", "reader");
    private static final WorkspaceContext WORKSPACE = new WorkspaceContext("reader", "workspace", "design", "project");
    private static final CatalogueDefinition NINE = new CatalogueDefinition("municipal-v1", true,
            List.of(new CatalogueDefinition.Root("Administrative services", "AS", "AdministrativeService")), List.of());
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final ArchitectureEditorService editor = mock(ArchitectureEditorService.class);
    private final CatalogueSourceJournal sources = mock(CatalogueSourceJournal.class);
    private final RetainedCatalogueDefinitions definitions = spy(new RetainedCatalogueDefinitions(sources));
    private final SelectedArchitectureAdmissionModel admission =
            new SelectedArchitectureAdmissionModel(resolver, editor, definitions);

    @BeforeEach void authorize() {
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(PROJECT);
    }

    private static String brief(UUID generation) {
        var seed = ProjectCatalogue.initial("administration", generation);
        String model = ArchitectureBrief.store(seed, Map.of("objective", "Design the municipal registry", "stage", "target"));
        return ArchitectureBrief.putReference(model, "commission", Map.of("identifier", "Architecture decision",
                "edition", "2026", "section", "4.2", "role", "binding", "scope", "architecture",
                "rule", ArchitectureBrief.RULE, "relation", "DEPENDS_ON"));
    }

    private static CatalogueSourceJournal.Snapshot generation(UUID id, CatalogueSourceJournal.Use use) {
        var workbook = new CatalogueSourceJournal.InputReference(use,
                use == CatalogueSourceJournal.Use.APPLIED ? "c".repeat(64) : null, 128);
        var absent = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0);
        return new CatalogueSourceJournal.Snapshot(id.toString(), Instant.EPOCH, workbook, absent, absent);
    }

    private void retain(UUID id, CatalogueDefinition definition) {
        var generation = generation(id, CatalogueSourceJournal.Use.APPLIED);
        when(sources.findRevision(id.toString())).thenReturn(Optional.of(generation));
        doReturn(definition).when(definitions).forGeneration(generation);
    }

    private static Document git(String commit, String dsl) {
        return new Document(Context.version(PROJECT, commit), dsl, "HISTORICAL", List.of(), List.of(), 0, null, "GIT_CHECKPOINT");
    }

    private static Document semantic(String commit, String dsl, long revision, long checkpointRevision, String state) {
        return new Document(Context.of(PROJECT, commit, revision), dsl, state, List.of(), List.of(),
                checkpointRevision, null, "WORKSPACE_REVISION");
    }

    @Test void reachableCheckpointFreezesItsOwnCatalogueCanonicalReferencesAndRequestedRoot() throws Exception {
        String historical = ProjectCatalogue.compareWith(brief(A), B);
        when(editor.read(PROJECT, FIRST, null)).thenReturn(git(FIRST, historical));
        when(editor.read(PROJECT, SECOND, null)).thenReturn(git(SECOND, brief(B)));
        retain(A, NINE);
        retain(B, CatalogueDefinition.standard());
        AnalysisScope requested = new AnalysisScope(Set.of("AS"), AnalysisMode.FULL);
        var result = admission.capture(WORKSPACE, FIRST, null, requested);
        assertEquals(RESOLVED, result.state());
        assertEquals("project", result.repositoryId());
        assertEquals("design", result.branch());
        assertEquals("workspace", result.workspaceScopeKey());
        assertEquals(FIRST, result.commit());
        assertNull(result.semanticRevision());
        assertEquals(StableIdentityHash.sha256(historical), result.modelSha256());
        assertEquals(A.toString(), result.catalogueRevision());
        assertEquals(A.toString(), result.executionCatalogueRevision());
        assertEquals("municipal-v1", result.catalogueDefinitionVersion());
        assertEquals(Set.of("AS"), result.rootCodes());
        assertEquals(requested, result.scope());
        assertEquals("Design the municipal registry", result.briefFields().get("objective"));
        assertEquals(Set.of("DEPENDS_ON"), result.excludedRelationTypes());
        var reference = result.references().getFirst();
        assertNotNull(reference.sourceId());
        assertNotNull(reference.sourceVersionId());
        assertNotNull(reference.sourceFragmentId());
        assertEquals("4.2", reference.values().get("section"));
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals(result, json.readValue(json.writeValueAsBytes(result), AnalysisModelBinding.class));
        assertEquals(9, admission.capture(WORKSPACE, FIRST, null, null).rootCodes().size());
        assertThrows(IllegalArgumentException.class, () -> admission.capture(WORKSPACE, SECOND, null, requested));
        verify(sources, never()).current();
        verify(definitions, never()).current();
    }

    @Test void legacyAndMissingOrUnretainedCatalogueNeverSubstituteAnotherGeneration() throws Exception {
        when(editor.read(PROJECT, FIRST, null)).thenReturn(git(FIRST, ""));
        var legacy = admission.capture(WORKSPACE, FIRST, null, null);
        assertEquals(CATALOGUE_UNRESOLVED, legacy.state());
        assertNull(legacy.catalogueRevision());
        assertNull(legacy.briefVersion());
        assertTrue(legacy.rootCodes().isEmpty());
        verify(sources).current();
        verify(definitions, never()).forGeneration(any());
        when(editor.read(PROJECT, FIRST, null)).thenReturn(git(FIRST, brief(A)));
        when(sources.findRevision(A.toString())).thenReturn(Optional.empty())
                .thenReturn(Optional.of(generation(A, CatalogueSourceJournal.Use.NOT_RETAINED)));
        for (int i = 0; i < 2; i++) {
            var result = admission.capture(WORKSPACE, FIRST, null, null);
            assertEquals(CATALOGUE_UNRESOLVED, result.state());
            assertEquals(A.toString(), result.catalogueRevision());
            assertNull(result.executionCatalogueRevision());
            assertEquals(A.toString(), result.analysisCatalogueRevision());
            assertNull(result.catalogueDefinitionVersion());
            assertEquals(Set.of(), result.rootCodes());
        }
        verify(definitions, never()).forGeneration(any());
    }

    @Test void unpinnedStartsFreezeTheAppliedExecutionCatalogueWhileModelProvenanceStaysUnknown() throws Exception {
        var first = generation(A, CatalogueSourceJournal.Use.APPLIED);
        var next = generation(B, CatalogueSourceJournal.Use.APPLIED);
        when(sources.current()).thenReturn(first).thenReturn(next);
        doReturn(NINE).when(definitions).forGeneration(first);
        doReturn(CatalogueDefinition.standard()).when(definitions).forGeneration(next);
        when(editor.read(PROJECT, null, null)).thenReturn(semantic(null, "", 0, 0, "READY"));
        var requested = new AnalysisScope(Set.of("AS"), AnalysisMode.FULL);
        var frozen = admission.capture(WORKSPACE, null, null, requested);
        assertEquals(CATALOGUE_UNRESOLVED, frozen.state());
        assertNull(frozen.catalogueRevision());
        assertNull(frozen.commit());
        assertEquals(A.toString(), frozen.analysisCatalogueRevision());
        assertEquals(A.toString(), frozen.executionCatalogueRevision());
        assertEquals("municipal-v1", frozen.catalogueDefinitionVersion());
        assertEquals(Set.of("AS"), frozen.rootCodes());
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals(frozen, json.readValue(json.writeValueAsBytes(frozen), AnalysisModelBinding.class));
        assertThrows(IllegalArgumentException.class, () -> admission.capture(WORKSPACE, null, null, requested));
        assertEquals(A.toString(), frozen.analysisCatalogueRevision());
        verify(sources, times(2)).current();
        verify(definitions, never()).current();
    }

    @Test void aPreexistingJsonBindingKeepsItsRecordedPinAndAnUnretainedCurrentGenerationClaimsNoExecutionBasis() throws Exception {
        retain(A, NINE);
        when(editor.read(PROJECT, FIRST, null)).thenReturn(git(FIRST, brief(A)));
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var recorded = admission.capture(WORKSPACE, FIRST, null, null);
        var fields = json.valueToTree(recorded).asObject();
        fields.remove("executionCatalogueRevision");
        var historical = json.treeToValue(fields, AnalysisModelBinding.class);
        assertNull(historical.executionCatalogueRevision());
        assertEquals(A.toString(), historical.analysisCatalogueRevision());
        verify(sources, never()).current();
        when(editor.read(PROJECT, SECOND, null)).thenReturn(git(SECOND, ""));
        when(sources.current()).thenReturn(generation(B, CatalogueSourceJournal.Use.NOT_RETAINED));
        var unknown = admission.capture(WORKSPACE, SECOND, null, null);
        assertEquals(CATALOGUE_UNRESOLVED, unknown.state());
        assertNull(unknown.catalogueRevision());
        assertNull(unknown.executionCatalogueRevision());
        assertNull(unknown.analysisCatalogueRevision());
        assertTrue(unknown.rootCodes().isEmpty());
        assertNull(unknown.catalogueDefinitionVersion());
    }

    @Test void unknownPolicyIsVisibleWithoutInventingAnEffectiveRule() throws IOException {
        retain(A, NINE);
        String future = brief(A).replaceFirst("(?m)^(\\s*x-brief\\.version\\s*:\\s*)[^;]+;", "$1 8;");
        when(editor.read(PROJECT, FIRST, null)).thenReturn(git(FIRST, future));
        var result = admission.capture(WORKSPACE, FIRST, null, null);
        assertEquals(PROFILE_UNRESOLVED, result.state());
        assertEquals("8", result.briefVersion());
        assertEquals("UNSUPPORTED_ARCHITECTURE_BRIEF", result.profileProblem());
        assertTrue(result.excludedRelationTypes().isEmpty());
        when(editor.read(PROJECT, FIRST, null)).thenReturn(git(FIRST, ProjectCatalogue.initial("administration", A)));
        assertEquals(BRIEF_UNASSIGNED, admission.capture(WORKSPACE, FIRST, null, null).state());
    }

    @Test void oldSemanticRevisionRequiresItsOwnHistoricalReceipt() throws Exception {
        String dsl = brief(A);
        retain(A, NINE);
        var document = semantic(SECOND, dsl, 2, 5, "HISTORICAL");
        when(editor.read(PROJECT, null, 2L)).thenReturn(document);
        when(editor.historicalCheckpoint(PROJECT, document, FIRST)).thenReturn(FIRST);
        var result = admission.capture(WORKSPACE, FIRST, 2L, null);
        assertEquals(RESOLVED, result.state());
        assertEquals(2L, result.semanticRevision());
        assertEquals(FIRST, result.commit());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> admission.capture(WORKSPACE, SECOND, 2L, null)).getStatusCode());
        var withoutReceipt = admission.capture(WORKSPACE, null, 2L, null);
        assertEquals(MODEL_UNCOMMITTED, withoutReceipt.state());
        assertNull(withoutReceipt.commit());
        assertEquals(StableIdentityHash.sha256(dsl), withoutReceipt.modelSha256());
    }

    @Test void aPendingReconciliationParentIsNeverClaimedAsAnEarlierPublishedRevision() throws Exception {
        String historical = brief(A);
        retain(A, NINE);
        var document = new Document(Context.of(PROJECT, SECOND, 4), historical, "HISTORICAL", List.of(), List.of(),
                4, "pending-reconciliation", "WORKSPACE_REVISION");
        when(editor.read(PROJECT, null, 4L)).thenReturn(document);
        when(editor.historicalCheckpoint(PROJECT, document, null)).thenReturn(FIRST);
        var result = admission.capture(WORKSPACE, null, 4L, null);
        assertEquals(FIRST, result.commit());
        assertEquals(RESOLVED, result.state());
        assertThrows(ResponseStatusException.class, () -> admission.capture(WORKSPACE, SECOND, 4L, null));
    }

    @Test void draftRecordsCheckpointBaseAndExactSemanticContentWithoutClaimingPublication() throws Exception {
        String dsl = brief(A);
        retain(A, NINE);
        when(editor.read(PROJECT, null, null)).thenReturn(semantic(FIRST, dsl, 6, 5, "READY"));
        var result = admission.capture(WORKSPACE, null, null, null);
        assertEquals(MODEL_UNCOMMITTED, result.state());
        assertEquals(6L, result.semanticRevision());
        assertEquals(FIRST, result.commit());
        assertEquals(StableIdentityHash.sha256(dsl), result.modelSha256());
        when(editor.read(PROJECT, null, 6L)).thenReturn(semantic(FIRST, dsl, 6, 6, "READY"));
        assertEquals(RESOLVED, admission.capture(WORKSPACE, FIRST, 6L, null).state());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> admission.capture(WORKSPACE, SECOND, 6L, null)).getStatusCode());
    }

    @Test void profileChoiceUsesTheSamePublishedModelAndTheEditorsRoleAndScope() throws Exception {
        String assigned = brief(A);
        retain(A, NINE);
        when(editor.read(PROJECT, null, 4L)).thenReturn(semantic(FIRST, assigned, 4, 4, "READY"));
        var controller = new AnalysisProfilePreviewController(admission, Optional.empty());
        var architect = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "reader", "", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ARCHITECT")));
        var reader = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "reader", "", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        var known = controller.read("project", "workspace", "design", null, 4L, architect).getBody();
        assertEquals(ProfilePreview.Decision.ASSIGNED, known.decision());
        assertTrue(known.editable());
        assertFalse(controller.read("project", "workspace", "design", null, 4L, reader).getBody().editable());
        assertThrows(ResponseStatusException.class, () -> controller.read("project", "other", "design", null, 4L, architect));
        var conflict = ArchitectureBrief.putReference(assigned, "second", Map.of("identifier", "Architecture decision",
                "edition", "2027", "section", "4.2", "role", "binding", "scope", "architecture"));
        when(editor.read(PROJECT, null, 4L)).thenReturn(semantic(FIRST, conflict, 4, 4, "READY"));
        assertEquals(ProfilePreview.Decision.CONFLICTING_EDITIONS,
                controller.read("project", "workspace", "design", null, 4L, architect).getBody().decision());
        var binding = admission.capture(WORKSPACE, null, 4L, null);
        assertEquals(PROFILE_UNRESOLVED, binding.state());
        assertTrue(binding.excludedRelationTypes().isEmpty());
        assertEquals("REFERENCE_PROFILE_CONFLICTING_EDITIONS", binding.profileProblem());
        String open = ArchitectureBrief.putReference(assigned, "second", Map.of("identifier", "Reference to review",
                "role", "open", "scope", "architecture"));
        when(editor.read(PROJECT, FIRST, null)).thenReturn(git(FIRST, open));
        var historical = controller.read("project", "workspace", "design", FIRST, null, architect);
        assertEquals("no-store", historical.getHeaders().getCacheControl());
        assertEquals(ProfilePreview.Decision.OPEN, historical.getBody().decision());
        assertFalse(historical.getBody().editable());
        assertEquals(Set.of("DEPENDS_ON"), admission.capture(WORKSPACE, FIRST, null, null).excludedRelationTypes());
        String noAssignments = ArchitectureBrief.store(ProjectCatalogue.initial("administration", A), Map.of("objective", "Develop services"));
        when(editor.read(PROJECT, FIRST, null)).thenReturn(git(FIRST, noAssignments));
        assertEquals(ProfilePreview.Decision.UNASSIGNED, controller.read("project", "workspace", "design", FIRST, null, architect).getBody().decision());
        assertEquals("Develop services", admission.capture(WORKSPACE, FIRST, null, null).briefFields().get("objective"));
    }

    @Test void authorizationRoutingAndUnstableWorkspaceFailBeforeBinding() throws Exception {
        for (var changed : List.of(new WorkspaceContext("other-reader", "workspace", "design", "project"),
                new WorkspaceContext("reader", "other-workspace", "design", "project"),
                new WorkspaceContext("reader", "workspace", "other-branch", "project"),
                new WorkspaceContext("reader", "workspace", "design", "other-project"))) {
            assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                    () -> admission.capture(changed, null, null, null)).getStatusCode());
        }
        verifyNoInteractions(editor);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> admission.capture(WORKSPACE, "HEAD", null, null)).getStatusCode());
        for (String state : List.of("VERSION_CHANGED", "CHECKPOINT_PENDING")) {
            when(editor.read(PROJECT, null, null)).thenReturn(semantic(FIRST, "", 7, 7, state));
            assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                    () -> admission.capture(WORKSPACE, null, null, null)).getStatusCode());
        }
        when(editor.read(PROJECT, null, null)).thenThrow(new IOException("repository unavailable"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> admission.capture(WORKSPACE, null, null, null)).getStatusCode());
        verifyNoInteractions(sources, definitions);
        verify(editor, never()).historicalCheckpoint(any(), any(), any());
    }
}
