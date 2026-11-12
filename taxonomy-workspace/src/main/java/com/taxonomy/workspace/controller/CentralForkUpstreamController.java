package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.CentralForkUpstreamService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.service.WorkspaceUpstreamService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public CentralForkUpstreamController(CentralForkUpstreamService upstream, WorkspaceResolver resolver) {
        this.upstream = upstream;
        this.resolver = resolver;
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
    public WorkspaceUpstreamService.Status fetch(@PathVariable String repositoryId, @RequestBody WorkspaceUpstreamService.Request request) throws IOException {
        return upstream.fetch(repositoryId, resolver.resolveCurrentUsername(), request);
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
    public WorkspaceUpstreamService.TagStatus fetchTag(@PathVariable String repositoryId, @RequestBody WorkspaceUpstreamService.TagRequest request) throws IOException {
        return upstream.fetchTag(repositoryId, resolver.resolveCurrentUsername(), request);
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
