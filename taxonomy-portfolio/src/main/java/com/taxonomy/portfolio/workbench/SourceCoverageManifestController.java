package com.taxonomy.portfolio.workbench;

import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** JSON attachment available from the saved snapshot workbench. */
@RestController
public class SourceCoverageManifestController {
    private final SourceCoverageManifestService service;
    private final WorkspaceResolver workspaces;

    public SourceCoverageManifestController(SourceCoverageManifestService service, WorkspaceResolver workspaces) {
        this.service = service;
        this.workspaces = workspaces;
    }

    @GetMapping(value = "/api/projects/{projectId}/architecture-workbench/{snapshotId}.source-coverage.json",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Export the source coverage manifest for a saved analysis",
            description = "Traces recorded source passages and architecture mapping decisions against a full Git checkpoint. "
                    + "Omit checkpoint to use the saved analysis commit. Unavailable or stale evidence is included as gaps.")
    public ResponseEntity<SourceCoverageManifest> sourceCoverage(@PathVariable Long projectId,
                                                                  @PathVariable String snapshotId,
                                                                  @RequestParam(required = false) String checkpoint) {
        var source = workspaces.resolveUnambiguousRepositoryForRead();
        var manifest = service.load(projectId, snapshotId, checkpoint, source);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("source-coverage.json").build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(manifest);
    }
}
