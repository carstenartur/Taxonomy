package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.CentralForkUpstreamService;
import com.taxonomy.workspace.service.WorkspaceFetchJournal;
import com.taxonomy.workspace.service.WorkspaceFetchRecovery;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.service.WorkspaceUpstreamService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@ConditionalOnProperty(prefix = "taxonomy.features.multi-repository-api", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/api/repositories/{repositoryId}/upstream")
public class CentralForkUpstreamController {
    private final CentralForkUpstreamService upstream;
    private final WorkspaceResolver resolver;
    private final WorkspaceFetchRecovery recovery;

    public CentralForkUpstreamController(CentralForkUpstreamService upstream, WorkspaceResolver resolver, WorkspaceFetchRecovery recovery) {
        this.upstream = upstream;
        this.resolver = resolver;
        this.recovery = recovery;
    }

    @GetMapping
    @Operation(summary = "Inspect a central fork's upstream",
            description = "Returns the source branch tip and the destination tracking ref for a visible fork after checking contributor access on both repositories.")
    public WorkspaceUpstreamService.Status inspect(@PathVariable String repositoryId) throws IOException {
        return upstream.inspect(repositoryId, resolver.resolveCurrentUsername());
    }

    @PostMapping("/fetch")
    @Operation(summary = "Fetch an exact upstream commit",
            description = "Copies verified objects from the selected source and advances its tracking ref with an expected commit. Independent fork branches retain their heads.")
    public ResponseEntity<WorkspaceUpstreamService.Status> fetch(@PathVariable String repositoryId, @RequestBody WorkspaceUpstreamService.Request request,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestHeader(name = "X-Causation-Id", required = false) String cause) throws IOException {
        var receipt = recovery.fetch(context(repositoryId), request, key, cause);
        return response(receipt.operationId(), receipt.result());
    }

    @GetMapping("/tag")
    @Operation(summary = "Inspect one named upstream tag",
            description = "Returns the tag object identity when it refers to a commit in the fetched source history.")
    public WorkspaceUpstreamService.TagStatus tag(@PathVariable String repositoryId, @RequestParam String name) throws IOException {
        return upstream.inspectTag(repositoryId, resolver.resolveCurrentUsername(), name);
    }

    @PostMapping("/fetch-tag")
    @Operation(summary = "Import one selected upstream tag",
            description = "Copies the selected lightweight or annotated Git tag at the confirmed identity. A conflicting local tag is preserved.")
    public ResponseEntity<WorkspaceUpstreamService.TagStatus> fetchTag(@PathVariable String repositoryId, @RequestBody WorkspaceUpstreamService.TagRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestHeader(name = "X-Causation-Id", required = false) String cause) throws IOException {
        var receipt = recovery.fetchTag(context(repositoryId), request, key, cause);
        return response(receipt.operationId(), receipt.tag());
    }

    @GetMapping("/operations/{id}")
    @Operation(summary = "Read a central fork's transfer receipt", description = "Requires current access to the recorded source and destination.")
    public WorkspaceFetchJournal.View status(@PathVariable String repositoryId, @PathVariable String id) throws IOException {
        return recovery.status(operationContext(repositoryId, id), id);
    }

    @PostMapping("/operations/{id}/retry")
    @Operation(summary = "Retry an exact central fork transfer", description = "Rechecks the original source, fork and actor before reconciling the recorded Git ref.")
    public ResponseEntity<?> retry(@PathVariable String repositoryId, @PathVariable String id) throws IOException {
        var receipt = recovery.retry(operationContext(repositoryId, id), id);
        return response(receipt.operationId(), receipt.response());
    }

    @DeleteMapping("/operations/{id}")
    @Operation(summary = "Archive a settled central fork transfer", description = "Retains the scoped outcome for the idempotency period.")
    public WorkspaceFetchJournal.View archive(@PathVariable String repositoryId, @PathVariable String id) throws IOException {
        return recovery.archive(operationContext(repositoryId, id), id);
    }

    private RepositoryContext operationContext(String repositoryId, String id) {
        return recovery.centralOperationContext(repositoryId, resolver.resolveCurrentUsername(), id);
    }

    private RepositoryContext context(String repositoryId) {
        return upstream.context(repositoryId, resolver.resolveCurrentUsername());
    }

    private static <T> ResponseEntity<T> response(String id, T body) {
        return ResponseEntity.status(body == null ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .header("X-Operation-Id", id).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> handleInvalidRequest() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(BranchHeadConflictException.class)
    public ResponseEntity<Void> handleChangedHead() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
