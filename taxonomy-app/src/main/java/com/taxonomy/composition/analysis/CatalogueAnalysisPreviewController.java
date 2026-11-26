package com.taxonomy.composition.analysis;

import com.taxonomy.analysis.usecase.PreviewRequirementUseCase;
import com.taxonomy.dto.CatalogueAnalysisPreview;
import com.taxonomy.dto.CatalogueAnalysisPreview.Request;
import com.taxonomy.shared.features.ConditionalOnFeature;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnFeature({"architecture", "analysis"})
public class CatalogueAnalysisPreviewController {
    private final PreviewRequirementUseCase preview;

    public CatalogueAnalysisPreviewController(PreviewRequirementUseCase preview) {
        this.preview = preview;
    }

    @Operation(summary = "Preview the requirement against the selected catalogue",
            description = "Computes bounded preliminary matches and catalogue vocabulary associations. "
                    + "An ancestor without a direct match has a null score. Nothing is saved as an assessment or architecture relation.")
    @PostMapping("/api/analysis/preview")
    public ResponseEntity<CatalogueAnalysisPreview> preview(@RequestBody Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preview.preview(request));
    }

    @Operation(summary = "Prepare the selected catalogue for interactive analysis",
            description = "Prepares requirement-independent vocabulary associations and requests local vector preparation. "
                    + "No requirement text is needed. An available embedding model warms asynchronously.")
    @PostMapping("/api/analysis/preview/prepare")
    public ResponseEntity<Void> prepare(@RequestBody Request request) {
        preview.prepare(request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
