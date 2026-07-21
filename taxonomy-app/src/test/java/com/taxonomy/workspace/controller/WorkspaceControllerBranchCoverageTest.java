package com.taxonomy.workspace.controller;

import com.taxonomy.dto.ContextComparison;
import com.taxonomy.dto.SemanticChange;
import com.taxonomy.dto.WorkspaceInfo;
import com.taxonomy.versioning.service.ContextCompareService;
import com.taxonomy.versioning.service.ContextHistoryService;
import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.service.SyncIntegrationService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceProjectionService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceControllerBranchCoverageTest {

    @Mock private WorkspaceManager workspaceManager;
    @Mock private WorkspaceResolver workspaceResolver;
    @Mock private ContextCompareService contextCompareService;
    @Mock private ContextHistoryService contextHistoryService;
    @Mock private ContextNavigationService contextNavigationService;
    @Mock private SyncIntegrationService syncIntegrationService;
    @Mock private WorkspaceProjectionService workspaceProjectionService;
    @Mock private SystemRepositoryService systemRepositoryService;

    private WorkspaceController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkspaceController(workspaceManager, workspaceResolver, contextCompareService,
                contextHistoryService, contextNavigationService, syncIntegrationService,
                workspaceProjectionService, systemRepositoryService);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
    }

    @Test
    void basicWorkspaceQueriesAndEvictionReturnServiceResults() {
        WorkspaceInfo info = info("draft");
        when(workspaceManager.getWorkspaceInfo("alice")).thenReturn(info);
        when(workspaceManager.listActiveWorkspaces()).thenReturn(List.of(info));
        when(workspaceManager.getActiveWorkspaceCount()).thenReturn(3);

        assertThat(controller.getCurrentWorkspace().getBody()).isEqualTo(info);
        assertThat(controller.listActiveWorkspaces().getBody()).containsExactly(info);
        assertThat(controller.getStats().getBody()).containsEntry("activeWorkspaces", 3);
        assertThat(controller.evictWorkspace("alice").getBody()).containsEntry("success", true);
        verify(workspaceManager).evictWorkspace("alice");
    }

    @Test
    void createWorkspaceCoversValidationSuccessAndFailure() {
        UserWorkspace workspace = workspace("w1", "My workspace");
        when(workspaceManager.createWorkspace("alice", "My workspace", "desc")).thenReturn(workspace);

        ResponseEntity<Map<String, Object>> success = controller.createWorkspace(
                Map.of("displayName", "My workspace", "description", "desc"));
        assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(success.getBody()).containsEntry("workspaceId", "w1").containsEntry("displayName", "My workspace");

        assertThat(controller.createWorkspace(Map.of("description", "desc")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.createWorkspace(Map.of("displayName", " ")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        when(workspaceManager.createWorkspace("alice", "Broken", null))
                .thenThrow(new IllegalStateException("database down"));
        ResponseEntity<Map<String, Object>> failure = controller.createWorkspace(Map.of("displayName", "Broken"));
        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(failure.getBody()).containsEntry("message", "database down");
    }

    @Test
    void renameAndDescriptionCoverSuccessValidationAndServiceErrors() {
        UserWorkspace renamed = workspace("w1", "Renamed");
        when(workspaceManager.renameWorkspace("alice", "w1", "Renamed")).thenReturn(renamed);
        assertThat(controller.renameWorkspace("w1", Map.of("displayName", "Renamed")).getBody())
                .containsEntry("displayName", "Renamed");
        assertThat(controller.renameWorkspace("w1", Map.of("displayName", " ")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        when(workspaceManager.renameWorkspace("alice", "missing", "Name"))
                .thenThrow(new IllegalArgumentException("not found"));
        assertThat(controller.renameWorkspace("missing", Map.of("displayName", "Name")).getBody())
                .containsEntry("error", "not found");

        when(workspaceManager.updateDescription("alice", "w1", "updated")).thenReturn(renamed);
        assertThat(controller.updateDescription("w1", Map.of("description", "updated")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        when(workspaceManager.updateDescription("alice", "missing", null))
                .thenThrow(new IllegalStateException("cannot update"));
        assertThat(controller.updateDescription("missing", Map.of()).getBody())
                .containsEntry("error", "cannot update");
    }

    @Test
    void lifecycleOperationsCoverSuccessAndRejectedOperations() {
        UserWorkspace workspace = workspace("w1", "Workspace");
        when(workspaceManager.switchWorkspace("alice", "w1")).thenReturn(workspace);
        when(workspaceManager.archiveWorkspace("w1", "alice")).thenReturn(workspace);
        when(workspaceManager.getWorkspaceById("w1")).thenReturn(workspace);

        assertThat(controller.switchWorkspace("w1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.archiveWorkspace("w1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.deleteWorkspace("w1").getBody()).containsEntry("deleted", "w1");
        assertThat(controller.getWorkspaceInfo("w1").getBody()).containsEntry("workspaceId", "w1");
        assertThat(controller.getWorkspaceInfo("missing").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        when(workspaceManager.switchWorkspace("alice", "bad")).thenThrow(new IllegalArgumentException("bad switch"));
        when(workspaceManager.archiveWorkspace("bad", "alice")).thenThrow(new IllegalArgumentException("bad archive"));
        doThrow(new IllegalArgumentException("bad delete")).when(workspaceManager).deleteWorkspace("bad", "alice");
        assertThat(controller.switchWorkspace("bad").getBody()).containsEntry("error", "bad switch");
        assertThat(controller.archiveWorkspace("bad").getBody()).containsEntry("error", "bad archive");
        assertThat(controller.deleteWorkspace("bad").getBody()).containsEntry("error", "bad delete");
    }

    @Test
    void compareCoversBranchCommitFilterAndIoFailure() throws Exception {
        ContextComparison comparison = new ContextComparison(null, null,
                new ContextComparison.DiffSummary(1, 0, 0, 1, 0, 0),
                List.of(
                        new SemanticChange("ADD", "ELEMENT", "E", "element", null, "after"),
                        new SemanticChange("ADD", "RELATION", "R", "relation", null, "after")),
                "diff");
        when(contextCompareService.compareBranches(any(), any())).thenReturn(comparison);
        when(contextCompareService.compareContexts(any(), any())).thenReturn(comparison);

        ResponseEntity<?> filtered = controller.compare("left", "right", null, null, Set.of("elements"));
        assertThat(filtered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((ContextComparison) filtered.getBody()).changes()).hasSize(1);

        ResponseEntity<?> unfiltered = controller.compare("left", "right", null, null, Set.of());
        assertThat(((ContextComparison) unfiltered.getBody()).changes()).hasSize(2);

        assertThat(controller.compare("left", "right", "c1", "c2", Set.of("relations")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        verify(contextCompareService).compareContexts(any(), any());

        when(contextCompareService.compareBranches(any(), any())).thenThrow(new IOException("compare failed"));
        ResponseEntity<?> failure = controller.compare("left", "right", null, null, null);
        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat((Map<?, ?>) failure.getBody()).containsEntry("message", "compare failed");
    }

    @Test
    void syncPublishAndLocalChangesCoverExplicitFallbackAndIoErrors() throws Exception {
        when(workspaceManager.getWorkspaceInfo("alice")).thenReturn(info("feature"));
        when(syncIntegrationService.syncFromShared("alice", "feature")).thenReturn("merge-1");
        when(syncIntegrationService.publishToShared("alice", "explicit")).thenReturn("merge-2");
        when(syncIntegrationService.getLocalChanges("alice", "feature")).thenReturn(2);

        assertThat(controller.syncFromShared(null).getBody())
                .containsEntry("branch", "feature").containsEntry("mergeCommit", "merge-1");
        assertThat(controller.publish("explicit").getBody())
                .containsEntry("branch", "explicit").containsEntry("mergeCommit", "merge-2");
        assertThat(controller.getLocalChanges(null).getBody())
                .containsEntry("changeCount", 2).containsEntry("hasUnpublishedChanges", true);

        when(workspaceManager.getWorkspaceInfo("alice")).thenReturn(null);
        when(syncIntegrationService.getLocalChanges("alice", "draft")).thenReturn(0);
        assertThat(controller.getLocalChanges(" ").getBody()).containsEntry("branch", "draft");

        when(syncIntegrationService.syncFromShared("alice", "bad")).thenThrow(new IOException("sync io"));
        when(syncIntegrationService.publishToShared("alice", "bad")).thenThrow(new IOException("publish io"));
        when(syncIntegrationService.getLocalChanges("alice", "bad")).thenThrow(new IOException("count io"));
        assertThat(controller.syncFromShared("bad").getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(controller.publish("bad").getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(controller.getLocalChanges("bad").getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void syncStateDirtyHistoryAndProjectionReturnMappedData() {
        SyncState state = new SyncState();
        state.setSyncStatus("AHEAD");
        state.setLastSyncedCommitId("s1");
        state.setLastPublishedCommitId("p1");
        state.setUnpublishedCommitCount(2);
        when(syncIntegrationService.getSyncState("alice")).thenReturn(state);
        when(syncIntegrationService.isDirty("alice")).thenReturn(true);
        when(contextHistoryService.getHistory("alice")).thenReturn(List.of());
        when(workspaceProjectionService.getProjectionInfo("alice")).thenReturn(Map.of("status", "READY"));

        assertThat(controller.getSyncState().getBody())
                .containsEntry("syncStatus", "AHEAD").containsEntry("unpublishedCommitCount", 2);
        assertThat(controller.isDirty().getBody())
                .containsEntry("dirty", true).containsEntry("syncStatus", "AHEAD");
        assertThat(controller.getHistory().getBody()).isEmpty();
        assertThat(controller.getOriginStack().getBody()).isEmpty();
        assertThat(controller.returnToHistoryEntry("missing").getBody())
                .containsEntry("success", false);
        assertThat(controller.getProjection().getBody()).containsEntry("status", "READY");
    }

    @Test
    void resolveDivergedCoversValidInvalidAndIoFailure() throws Exception {
        when(workspaceManager.getWorkspaceInfo("alice")).thenReturn(info("feature"));
        when(syncIntegrationService.resolveDiverged("alice", "feature",
                SyncIntegrationService.DivergedStrategy.MERGE)).thenReturn("merged");

        assertThat(controller.resolveDiverged("merge", null).getBody())
                .containsEntry("strategy", "MERGE").containsEntry("message", "merged");
        assertThat(controller.resolveDiverged("unknown", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        when(syncIntegrationService.resolveDiverged("alice", "feature",
                SyncIntegrationService.DivergedStrategy.TAKE_SHARED)).thenThrow(new IOException("merge io"));
        assertThat(controller.resolveDiverged("TAKE_SHARED", null).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void provisioningAndTopologyCoverAbsentPresentSuccessAndFailure() {
        when(workspaceManager.findUserWorkspace("alice")).thenReturn(null);
        assertThat(controller.getProvisioningStatus().getBody()).containsEntry("status", "NOT_PROVISIONED");

        UserWorkspace workspace = workspace("w1", "Workspace");
        workspace.setSourceRepositoryId("system");
        workspace.setProvisioningError("previous error");
        when(workspaceManager.findUserWorkspace("alice")).thenReturn(workspace);
        assertThat(controller.getProvisioningStatus().getBody())
                .containsEntry("status", "READY").containsEntry("topologyMode", "INTERNAL_SHARED");

        when(workspaceManager.provisionWorkspaceRepository("alice")).thenReturn(workspace);
        assertThat(controller.provisionWorkspace().getBody())
                .containsEntry("status", "READY").containsEntry("branch", "draft");
        when(workspaceManager.provisionWorkspaceRepository("alice")).thenThrow(new IllegalStateException("provision failed"));
        assertThat(controller.provisionWorkspace().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId("system");
        repository.setDisplayName("System Repository");
        repository.setDefaultBranch("draft");
        repository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(repository);
        assertThat(controller.getTopology().getBody())
                .containsEntry("mode", "INTERNAL_SHARED")
                .containsEntry("systemRepositoryId", "system");
    }

    private static WorkspaceInfo info(String branch) {
        return new WorkspaceInfo("w1", "alice", "Workspace", branch, "draft", false,
                null, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
    }

    private static UserWorkspace workspace(String id, String displayName) {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setWorkspaceId(id);
        workspace.setUsername("alice");
        workspace.setDisplayName(displayName);
        workspace.setDescription("desc");
        workspace.setCurrentBranch("draft");
        workspace.setBaseBranch("draft");
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
        workspace.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        workspace.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        workspace.setLastAccessedAt(Instant.parse("2026-01-02T00:00:00Z"));
        return workspace;
    }
}
