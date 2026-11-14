package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.service.GitNativeSyncIntegrationService;
import com.taxonomy.workspace.service.WorkspacePublicationJournal;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspace/publications")
public class WorkspacePublicationController {
    private final WorkspaceResolver selection;
    private final GitNativeSyncIntegrationService synchronization;

    public WorkspacePublicationController(WorkspaceResolver selection, GitNativeSyncIntegrationService synchronization) {
        this.selection=selection;
        this.synchronization=synchronization;
    }

    @GetMapping("/{id}")
    @Operation(summary="Read a workspace publication receipt",description="Returns the original source, stable initiator, current phase and result for a publication in the selected working copy and branch.")
    public WorkspacePublicationJournal.View status(@PathVariable String id) {
        return synchronization.publicationStatus(selection.resolveCurrentRepositoryContextForRead(),id);
    }

    @PostMapping("/{id}/retry")
    @Operation(summary="Resume an interrupted workspace publication",description="Rechecks the initiating identity and current source access before continuing the recorded Git and portfolio phases.")
    public ResponseEntity<WorkspacePublicationJournal.View> retry(@PathVariable String id) {
        var row=synchronization.retryPublication(selection.resolveCurrentRepositoryContextForRead(),id);
        return ResponseEntity.status(row.completedAt()==null ? 202 : "DENIED".equals(row.status()) ? 403 :
                "MANUAL_REVIEW".equals(row.status()) ? 409 : 200).header("X-Operation-Id",row.operationId()).body(row);
    }

    @DeleteMapping("/{id}")
    @Operation(summary="Archive a settled workspace publication",description="Archives a terminal publication in the selected scope and records its deleting actor separately from the original initiator.")
    public WorkspacePublicationJournal.View archive(@PathVariable String id) {
        return synchronization.archivePublication(selection.resolveCurrentRepositoryContextForRead(),id);
    }
}
