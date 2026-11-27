package com.taxonomy.composition.analysis;

import com.taxonomy.analysis.usecase.AnalysisAdmissionModelPort;
import com.taxonomy.analysis.usecase.PreviewRequirementUseCase;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.snapshot.RetainedCataloguePreview;
import com.taxonomy.dto.CatalogueAnalysisPreview;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.snapshot.CataloguePreviewIndex;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.CatalogueSourceIdentity;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.catalog.snapshot.SemanticCataloguePreview;
import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.security.access.ApiAccessPolicy;
import com.taxonomy.security.access.ApiPermission;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogueAnalysisPreviewControllerTest {
    private static final String REVISION = "00000000-0000-0000-0000-000000000001";
    private static final String COMMIT = "a".repeat(40);
    private static final WorkspaceContext WORKSPACE = new WorkspaceContext("reader", "desk", "design", "civic");
    private static final CatalogueSourceIdentity SOURCE = new CatalogueSourceIdentity("civic", "desk", "design", COMMIT);
    private final AnalysisAdmissionModelPort models = mock(AnalysisAdmissionModelPort.class);
    private final WorkspaceResolver workspaces = mock(WorkspaceResolver.class);
    private final CatalogueSnapshotService archives = mock(CatalogueSnapshotService.class);
    private final SemanticCataloguePreview semantic = mock(SemanticCataloguePreview.class);
    private final CatalogueAnalysisPreviewController controller =
            new CatalogueAnalysisPreviewController(new PreviewRequirementUseCase(models, workspaces,
                    (key, defaultValue) -> 240, new RetainedCataloguePreview(archives, semantic)));

    @Test
    void editedTextUsesTheAuthorizedPinnedCatalogueAndContextAncestorsHaveNoScore() {
        admit();
        var roots = List.of(root());
        when(archives.captureRevision(SOURCE, REVISION, Set.of())).thenReturn(roots)
                .thenReturn(roots).thenThrow(new IllegalStateException("Missing archive"));
        var result = controller.preview(request("permits", Set.of("BP")));
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        var first = result.getBody();
        assertThat(first.catalogueRevision()).isEqualTo(REVISION);
        assertThat(first.commit()).isEqualTo(COMMIT);
        assertThat(first.method()).isEqualTo("CATALOGUE_LEXICAL");
        assertThat(first.nodes()).extracting(CatalogueAnalysisPreview.Node::code).containsExactly("BP", "BP-1");
        assertThat(first.nodes().getFirst().score()).isNull();
        assertThat(first.nodes().getLast().score()).isPositive();
        assertThat(controller.preview(request("zzzxq", Set.of())).getBody().nodes()).isEmpty();
        var unavailable = assertThrows(ResponseStatusException.class, () -> controller.preview(request("permit", Set.of())));
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(models, times(3)).capture(WORKSPACE, COMMIT, null, AnalysisScope.full());
    }

    @Test
    void warmupRequiresTheSelectedCatalogueAndReusesItsIndexWhenTextArrives() {
        admit();
        var roots = List.of(root());
        when(archives.captureRevision(SOURCE, REVISION, Set.of())).thenReturn(roots);
        assertThat(ApiAccessPolicy.resolve("POST", "/api/analysis/preview/prepare").permission()).isEqualTo(ApiPermission.ANALYSIS_RUN);
        var prepared = controller.prepare(request(null, Set.of()));
        assertThat(prepared.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var captured = org.mockito.ArgumentCaptor.forClass(CataloguePreviewIndex.class);
        verify(semantic).prepare(captured.capture(), eq(roots));
        controller.preview(request("permits", Set.of("BP")));
        verify(semantic).query(same(captured.getValue()), eq(roots), eq("permits"), eq(Set.of("BP")));
        verify(archives, times(2)).captureRevision(SOURCE, REVISION, Set.of());
    }

    @Test
    void realSemanticResultMayBeEmptyWithoutBeingReplacedByLexicalHits() {
        admit();
        var roots = List.of(root());
        when(archives.captureRevision(SOURCE, REVISION, Set.of())).thenReturn(roots);
        when(semantic.query(any(), eq(roots), eq("permits"), eq(Set.of())))
                .thenReturn(Optional.of(new CataloguePreviewIndex.Result(List.of(), List.of(), List.of())));
        var result = controller.preview(request("permits", Set.of())).getBody();
        assertThat(result.method()).isEqualTo("CATALOGUE_ONNX");
        assertThat(result.nodes()).isEmpty();
    }

    @Test
    void inputScopeAndAnalysisPermissionAreRequiredBeforeAnyScoring() {
        assertThat(ApiAccessPolicy.resolve("POST", "/api/analysis/preview").permission()).isEqualTo(ApiPermission.ANALYSIS_RUN);
        for (String invalid : List.of("  ", "x".repeat(241))) {
            var error = assertThrows(ResponseStatusException.class, () -> controller.preview(request(invalid, Set.of())));
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verifyNoInteractions(workspaces, models);
        admit();
        var invalidRoot = assertThrows(ResponseStatusException.class, () -> controller.preview(request("permits", Set.of("foreign"))));
        assertThat(invalidRoot.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(archives, semantic);
    }

    private void admit() {
        when(workspaces.resolveUnambiguousContextForRead()).thenReturn(WORKSPACE);
        when(models.capture(WORKSPACE, COMMIT, null, AnalysisScope.full())).thenReturn(new AnalysisModelBinding(
                AnalysisModelBinding.State.BRIEF_UNASSIGNED, "civic", "desk", "design", COMMIT, null, "GIT_CHECKPOINT",
                "a".repeat(64), REVISION, "1", Set.of("BP"), AnalysisScope.full(), null,
                Map.of(), List.of(), Set.of(), null));
    }

    private static CatalogueAnalysisPreview.Request request(String text, Set<String> roots) {
        return new CatalogueAnalysisPreview.Request(text, roots, COMMIT, null);
    }

    private static RootCatalogueSnapshot root() {
        var unused = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0);
        var source = new CatalogueSourceJournal.Snapshot(REVISION, Instant.EPOCH, unused, unused, unused);
        var nodes = List.of(node("BP", null, "Operations"), node("BP-1", "BP", "Issue permits"));
        return new RootCatalogueSnapshot(2, SOURCE, "BP", nodes,
                new CatalogueOverlayService.OverlayMetadata(false, "test", "test", "1", null, 0), source);
    }

    private static RootCatalogueSnapshot.Node node(String code, String parent, String name) {
        var node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(name);
        node.setParentCode(parent);
        node.setTaxonomyRoot("BP");
        node.setLevel(parent == null ? 0 : 1);
        return RootCatalogueSnapshot.Node.capture(node,
                new CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null), false);
    }
}
