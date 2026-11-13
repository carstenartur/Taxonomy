package com.taxonomy.versioning.controller;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.WorkspaceBranchDeletion;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/dsl/branch")
public class VariantDeletionController {
    private final WorkspaceResolver resolver;
    private final WorkspaceBranchDeletion deletion;

    public VariantDeletionController(WorkspaceResolver resolver, WorkspaceBranchDeletion deletion) {
        this.resolver = resolver;
        this.deletion = deletion;
    }

    @GetMapping("/deletion-preview")
    @Operation(summary = "Review a removable working-copy variant and its exact Git head",
            description = "Checks deletion authority in the selected working copy and returns a strong ETag "
                    + "for the named variant at its current Git commit. The response cannot be cached.")
    public ResponseEntity<WorkspaceBranchDeletion.Preview> preview(@RequestParam String name) throws IOException {
        var value = deletion.preview(resolver.resolveUnambiguousRepositoryForRead(), name);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(value.etag()).body(value);
    }

    @DeleteMapping
    @Operation(summary = "Delete the exact variant confirmed in the selected working copy",
            description = "Rechecks current authority and deletes the named variant only when If-Match "
                    + "matches the reviewed source, branch and commit. A missing precondition returns 428 "
                    + "and a changed confirmation returns 412.")
    public ResponseEntity<Map<String, Object>> delete(@RequestParam String name,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws IOException {
        deletion.delete(resolver.resolveUnambiguousRepositoryForRead(), name, ifMatch);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("deleted", name, "success", true));
    }

    @ExceptionHandler(BranchHeadConflictException.class)
    ResponseEntity<?> changed() { return error(HttpStatus.PRECONDITION_FAILED, "The reviewed variant changed"); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException ex) { return error(HttpStatus.BAD_REQUEST, ex.getMessage()); }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> unavailable(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("error", ex.getReason() == null ? "Variant unavailable" : ex.getReason()));
    }

    private static ResponseEntity<?> error(HttpStatus status, String detail) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", detail));
    }
}
