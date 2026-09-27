package com.taxonomy.composition.analysis;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Connects text-driven exports to the same analysis authority as the analysis page. */
@Service
public class AnalysisDiagramExportService {
    private final AnalyzeRequirementUseCase analysis;
    private final AnalysisRuntimeSettings settings;
    private final WorkspaceResolver workspaces;
    private final RepositoryStateService repositoryState;
    private final TaxonomyService catalogue;
    private final DiagramProjectionService projection;

    public AnalysisDiagramExportService(AnalyzeRequirementUseCase analysis,
                                       AnalysisRuntimeSettings settings,
                                       WorkspaceResolver workspaces,
                                       RepositoryStateService repositoryState,
                                       TaxonomyService catalogue,
                                       DiagramProjectionService projection) {
        this.analysis = analysis;
        this.settings = settings;
        this.workspaces = workspaces;
        this.repositoryState = repositoryState;
        this.catalogue = catalogue;
        this.projection = projection;
    }

    public DiagramModel analyzeAndProject(String original) {
        int textLimit = Math.max(100, Math.min(100_000, settings.getInt("limits.max-business-text", 5_000)));
        if (original == null || original.isBlank() || original.length() > textLimit) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Business requirement is missing or exceeds the configured character limit.");
        }
        if (!catalogue.isInitialized()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Taxonomy is not ready; no analysis was started.");
        }
        String username = workspaces.resolveCurrentUsername();
        WorkspaceContext workspace;
        try {
            repositoryState.ensureWorkspaceState(username);
            workspace = workspaces.resolveCurrentContext();
            if (workspace == null) throw new IllegalStateException("Missing workspace context");
        } catch (AccessDeniedException | ResponseStatusException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Workspace context is unavailable; no analysis was started.", unavailable);
        }
        int nodeLimit = Math.max(1, Math.min(1_000, settings.getInt("limits.max-architecture-nodes", 50)));
        var outcome = analysis.analyzePreview(new AnalyzeRequirementCommand(
                original, true, nodeLimit, null, username, workspace));
        AnalysisResult result = outcome == null ? null : outcome.analysisResult();
        if (result == null || !("SUCCESS".equals(result.getStatus()) || "PARTIAL".equals(result.getStatus()))
                || result.getArchitectureView() == null
                || result.getArchitectureView().getIncludedElements() == null
                || result.getArchitectureView().getIncludedElements().isEmpty()) {
            // A failed search is not permission to construct an unrelated score-only diagram.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The analysis did not produce an exportable architecture. Inspect its evidence before retrying.");
        }
        var view = result.getArchitectureView();
        String title = original.length() > 60 ? original.substring(0, 57) + "..." : original;
        boolean coverageLabelsPartial = view.getAnalysisCoverage() != null && view.getAnalysisCoverage().hasOpenEvaluations();
        if (!coverageLabelsPartial && ("PARTIAL".equals(result.getStatus())
                || view.getRelationSearchReport() != null && !view.getRelationSearchReport().isSearchExhausted())) {
            title += " — PARTIAL / TEILERGEBNIS: incomplete analysis / unvollständige Analyse";
        }
        // The use case already applied the requested analysis node limit. A transient
        // diagram policy must not reselect the model or remove its evidence endpoints.
        return projection.projectRaw(view, title);
    }
}
