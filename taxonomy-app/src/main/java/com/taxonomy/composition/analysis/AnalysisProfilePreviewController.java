package com.taxonomy.composition.analysis;

import com.taxonomy.editor.ArchitectureEditPermission;
import com.taxonomy.shared.features.ConditionalOnFeature;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@ConditionalOnFeature({"architecture", "analysis"})
public class AnalysisProfilePreviewController {
    private final SelectedArchitectureAdmissionModel models;
    private final Optional<ArchitectureEditPermission> editPermission;

    public AnalysisProfilePreviewController(SelectedArchitectureAdmissionModel models, Optional<ArchitectureEditPermission> editPermission) {
        this.models = models;
        this.editPermission = editPermission;
    }

    @GetMapping("/api/architecture/editor/analysis-brief")
    @Operation(summary = "Read the architecture brief used by the selected analysis model",
            description = "Returns the authored brief, its retained references and their assignment status for the authorized "
                    + "repository, workspace and branch. The selected Git checkpoint or semantic revision determines the "
                    + "profile. Editing availability follows the same role and historical-version rules as the architecture editor.")
    @ApiResponse(responseCode = "200", description = "The versioned brief and this caller's editing capability")
    @ApiResponse(responseCode = "400", description = "The requested version is malformed")
    @ApiResponse(responseCode = "404", description = "The selected version is unavailable")
    @ApiResponse(responseCode = "409", description = "The selected model context or version conflicts with the request")
    public ResponseEntity<ProfilePreview> read(
            @Parameter(description = "Exact logical repository in the authenticated selection") @RequestParam String repositoryId,
            @Parameter(description = "Exact selected workspace scope key, including the shared scope") @RequestParam String workspaceScopeKey,
            @Parameter(description = "Selected Git branch") @RequestParam String branch,
            @Parameter(description = "Optional full SHA of the selected reachable Git checkpoint") @RequestParam(required = false) String expectedCommit,
            @Parameter(description = "Optional retained workspace semantic revision") @RequestParam(required = false) Long expectedSemanticRevision,
            Authentication authentication) {
        var preview = models.preview(repositoryId, workspaceScopeKey, branch, expectedCommit, expectedSemanticRevision,
                ArchitectureEditPermission.allowed(authentication, editPermission));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preview);
    }
}
