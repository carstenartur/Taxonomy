package com.taxonomy.versioning.controller;

import com.taxonomy.relations.controller.GitHttpPrecondition;
import com.taxonomy.relations.controller.RelationApiController;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight strong-ETag endpoint for direct hypothesis review clients. */
@RestController
@RequestMapping("/api/dsl/hypotheses")
@Tag(name = "DSL Versioning")
public class HypothesisHeadApiController {

    private final RelationBranchProjectionReadinessService readinessService;
    private final WorkspaceResolver workspaceResolver;

    public HypothesisHeadApiController(
            RelationBranchProjectionReadinessService readinessService,
            WorkspaceResolver workspaceResolver) {
        this.readinessService = readinessService;
        this.workspaceResolver = workspaceResolver;
    }

    @Operation(
            summary = "Read the exact Git head used as hypothesis review precondition",
            description = "Returns the selected repository/workspace branch head as a strong ETag without loading projection rows")
    @GetMapping("/head")
    public ResponseEntity<Void> readHead() {
        RepositoryContext context = workspaceResolver
                .resolveCurrentRepositoryContext();
        String head = readinessService.readCurrentHead(context);
        if (head == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header(
                            RelationApiController.PROJECTION_STATE_HEADER,
                            ReadinessState.BRANCH_MISSING.name())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .build();
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.ETAG, GitHttpPrecondition.etag(head))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
