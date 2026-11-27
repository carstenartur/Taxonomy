package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import com.taxonomy.catalog.snapshot.RetainedCataloguePreview;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.CatalogueAnalysisPreview;
import com.taxonomy.dto.CatalogueAnalysisPreview.Request;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/** The host exposes this use case only when both analysis and architecture admission are installed. */
@Service
@Lazy
public class PreviewRequirementUseCase {
    private final AnalysisAdmissionModelPort models;
    private final WorkspaceResolver workspaces;
    private final AnalysisRuntimeSettings settings;
    private final RetainedCataloguePreview catalogues;

    public PreviewRequirementUseCase(AnalysisAdmissionModelPort models, WorkspaceResolver workspaces,
                                     AnalysisRuntimeSettings settings, RetainedCataloguePreview catalogues) {
        this.models = models;
        this.workspaces = workspaces;
        this.settings = settings;
        this.catalogues = catalogues;
    }

    public CatalogueAnalysisPreview preview(Request request) {
        if (request == null || request.businessText() == null || request.businessText().isBlank()
                || request.businessText().length() > AiPromptBudgetPolicy.businessTextLimit(settings)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A requirement within the configured length limit is required");
        }
        var selection = admit(request);
        var ranking = selection.catalogue().query(request.businessText(), selection.roots());
        var binding = selection.binding();
        return ranking.at(binding.analysisCatalogueRevision(), binding.commit(), binding.semanticRevision());
    }

    public void prepare(Request request) {
        admit(request).catalogue().prepare();
    }

    private record Selection(AnalysisModelBinding binding, Set<String> roots, RetainedCataloguePreview.Selection catalogue) { }

    private Selection admit(Request request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A preview selection is required");
        var workspace = workspaces.resolveUnambiguousContextForRead();
        var binding = AnalysisRequestModelAdmission.capture(models, workspace, request.expectedCommit(),
                request.expectedSemanticRevision(), AnalysisScope.full());
        var source = AdmittedCatalogue.source(binding, workspace);
        String revision = AdmittedCatalogue.revision(binding);
        AnalysisScope scope;
        try {
            scope = new AnalysisScope(request.taxonomyRoots(), AnalysisMode.TAXONOMIES_ONLY);
            scope.validateRoots(binding.rootCodes());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        try {
            return new Selection(binding, scope.taxonomyRoots(), catalogues.open(source, revision));
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The retained catalogue is unavailable", unavailable);
        }
    }
}
