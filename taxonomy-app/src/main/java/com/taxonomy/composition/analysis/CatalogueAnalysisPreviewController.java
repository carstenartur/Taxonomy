package com.taxonomy.composition.analysis;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import com.taxonomy.analysis.usecase.AdmittedCatalogue;
import com.taxonomy.analysis.usecase.AnalysisRequestModelAdmission;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.snapshot.CataloguePreviewIndex;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.CatalogueSourceIdentity;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.catalog.snapshot.SemanticCataloguePreview;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.shared.features.ConditionalOnFeature;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

@RestController
@ConditionalOnFeature({"architecture", "analysis"})
public class CatalogueAnalysisPreviewController {
    private final SelectedArchitectureAdmissionModel models;
    private final WorkspaceResolver workspaces;
    private final CatalogueSnapshotService catalogues;
    private final AnalysisRuntimeSettings settings;
    private final SemanticCataloguePreview semantic;
    private final LinkedHashMap<CatalogueSourceJournal.Snapshot, CataloguePreviewIndex> indexes = new LinkedHashMap<>(4, 0.75f, true);

    public CatalogueAnalysisPreviewController(SelectedArchitectureAdmissionModel models, WorkspaceResolver workspaces,
            CatalogueSnapshotService catalogues, AnalysisRuntimeSettings settings, SemanticCataloguePreview semantic) {
        this.models = models;
        this.workspaces = workspaces;
        this.catalogues = catalogues;
        this.settings = settings;
        this.semantic = semantic;
    }

    public record Request(String businessText, Set<String> taxonomyRoots, String expectedCommit, Long expectedSemanticRevision) { }
    private record Selection(String revision, AnalysisModelBinding binding, Set<String> roots,
                             List<RootCatalogueSnapshot> snapshot, CataloguePreviewIndex index) { }

    public record Node(String code, String parentCode, String taxonomyRoot, String nameEn, String nameDe, Double score) {
        static Node of(CataloguePreviewIndex.Match match, Double score) {
            return new Node(match.code(), match.parentCode(), match.root(), match.nameEn(), match.nameDe(), score);
        }
    }
    public record Preview(String method, String catalogueRevision, String commit, Long semanticRevision,
                          List<Node> nodes, List<CataloguePreviewIndex.Association> associations) { }

    @Operation(summary = "Preview the requirement against the selected catalogue",
            description = "Computes bounded preliminary matches and catalogue vocabulary associations. "
                    + "An ancestor without a direct match has a null score. Nothing is saved as an assessment or architecture relation.")
    @PostMapping("/api/analysis/preview")
    public ResponseEntity<Preview> preview(@RequestBody Request request) {
        if (request == null || request.businessText() == null || request.businessText().isBlank()
                || request.businessText().length() > AnalysisRuntimeSettings.businessTextLimit(settings)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A requirement within the configured length limit is required");
        }
        var selection = selection(request);
        var semanticResult = semantic.query(selection.index(), selection.snapshot(), request.businessText(), selection.roots());
        var result = semanticResult.orElseGet(() -> selection.index().query(request.businessText(), selection.roots()));
        var nodes = new ArrayList<Node>();
        result.ancestors().forEach(match -> nodes.add(Node.of(match, null)));
        result.matches().forEach(match -> nodes.add(Node.of(match, match.score())));
        var preview = new Preview(semanticResult.isPresent() ? "CATALOGUE_ONNX" : "CATALOGUE_LEXICAL", selection.revision(),
                selection.binding().commit(), selection.binding().semanticRevision(), List.copyOf(nodes), result.associations());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preview);
    }

    @Operation(summary = "Prepare the selected catalogue for interactive analysis",
            description = "Prepares requirement-independent vocabulary associations and requests local vector preparation. "
                    + "No requirement text is needed. An available embedding model warms asynchronously.")
    @PostMapping("/api/analysis/preview/prepare")
    public ResponseEntity<Void> prepare(@RequestBody Request request) {
        var selection = selection(request);
        semantic.prepare(selection.index(), selection.snapshot());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private Selection selection(Request request) {
        var workspace = workspaces.resolveUnambiguousContextForRead();
        var binding = AnalysisRequestModelAdmission.capture(models, workspace, request.expectedCommit(),
                request.expectedSemanticRevision(), AnalysisScope.full());
        AdmittedCatalogue.requireWorkspace(binding, workspace);
        String revision = AdmittedCatalogue.revision(binding);
        AnalysisScope scope;
        try {
            scope = new AnalysisScope(request.taxonomyRoots(), AnalysisMode.TAXONOMIES_ONLY);
            scope.validateRoots(binding.rootCodes());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        var source = new CatalogueSourceIdentity(binding.repositoryId(), workspace.workspaceId(),
                binding.branch(), binding.commit(), binding.commit() == null ? binding.modelSha256() : null);
        List<RootCatalogueSnapshot> roots;
        try {
            // Recheck immutable archive availability and integrity on warm hits too.
            roots = catalogues.captureRevision(source, revision, Set.of());
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The retained catalogue is unavailable", unavailable);
        }
        var generation = roots.getFirst().catalogueProvenance();
        if (!revision.equals(generation.id())) throw new IllegalStateException("Unexpected retained catalogue generation");
        return new Selection(revision, binding, scope.taxonomyRoots(), roots, index(generation, roots));
    }

    /** Share only derived catalogue data across requests, after each caller's own admission and archive read. */
    private synchronized CataloguePreviewIndex index(CatalogueSourceJournal.Snapshot generation, List<RootCatalogueSnapshot> roots) {
        var result = indexes.get(generation);
        if (result == null) {
            result = new CataloguePreviewIndex(roots);
            if (indexes.size() == 2) indexes.pollFirstEntry();
            indexes.put(generation, result);
        }
        return result;
    }
}
