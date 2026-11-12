package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.service.WorkspaceUpstreamService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/workspace/upstream")
public class WorkspaceUpstreamController {
    private final WorkspaceUpstreamService upstream;
    private final WorkspaceResolver resolver;

    public WorkspaceUpstreamController(WorkspaceUpstreamService upstream, WorkspaceResolver resolver) {
        this.upstream = upstream;
        this.resolver = resolver;
    }

    @GetMapping
    @Operation(summary = "Inspect the selected working copy's upstream",
            description = "Returns the source branch tip and the destination tracking ref for an owned personal working copy.")
    public WorkspaceUpstreamService.Status inspect() throws IOException {
        return upstream.inspect(resolver.resolveCurrentRepositoryContextForRead());
    }

    @PostMapping("/fetch")
    @Operation(summary = "Fetch an exact upstream commit",
            description = "Copies verified objects from the selected source and advances its tracking ref with an expected commit. The local working branch retains its current head until reviewed integration.")
    public WorkspaceUpstreamService.Status fetch(@RequestBody WorkspaceUpstreamService.Request request) throws IOException {
        return upstream.fetch(resolver.resolveCurrentRepositoryContextForRead(), request);
    }

    @GetMapping("/tag")
    @Operation(summary = "Inspect one named upstream tag",
            description = "Returns the tag object identity when it refers to a commit in the fetched source history.")
    public WorkspaceUpstreamService.TagStatus tag(@RequestParam String name) throws IOException {
        return upstream.inspectTag(resolver.resolveCurrentRepositoryContextForRead(), name);
    }

    @PostMapping("/fetch-tag")
    @Operation(summary = "Import one selected upstream tag",
            description = "Copies the selected lightweight or annotated Git tag at the confirmed identity. A conflicting local tag is preserved.")
    public WorkspaceUpstreamService.TagStatus fetchTag(@RequestBody WorkspaceUpstreamService.TagRequest request) throws IOException {
        return upstream.fetchTag(resolver.resolveCurrentRepositoryContextForRead(), request);
    }

    @ExceptionHandler(BranchHeadConflictException.class)
    public ResponseEntity<Void> handleChangedHead() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
