package com.taxonomy.composition.dsl.controller;

import com.taxonomy.composition.dsl.service.DslDocumentOperationsFacade;
import com.taxonomy.versioning.controller.DslReadWorkspaceContextResolver;

import com.taxonomy.dsl.storage.DslCommit;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.versioning.service.DslOperationsFacade;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DslDocumentApiControllerContextBoundaryTest {

    @Mock
    private DslOperationsFacade dslOperationsFacade;

    @Mock
    private WorkspaceResolver workspaceResolver;

    @Mock
    private RepositoryStateService repositoryStateService;

    @Mock private DslDocumentOperationsFacade documents;

    private DslDocumentApiController controller;

    @BeforeEach
    void setUp() {
        controller = new DslDocumentApiController(
                documents, dslOperationsFacade,
                workspaceResolver,
                new DslReadWorkspaceContextResolver(workspaceResolver, repositoryStateService));
    }

    @Test
    void getHistoryResolvesWorkspaceContextOnceAndPassesItExplicitly() throws Exception {
        WorkspaceContext workspaceContext = new WorkspaceContext("alice", "alice-ws", "draft");
        DslCommit commit = new DslCommit("abc123", "alice", Instant.parse("2026-01-01T00:00:00Z"), "message");
        ViewContext viewContext = new ViewContext("abc123", "draft", Instant.parse("2026-01-01T00:00:00Z"),
                true, false, false);

        when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        when(workspaceResolver.resolveCurrentContext()).thenReturn(workspaceContext);
        when(dslOperationsFacade.getDslHistory("draft", workspaceContext)).thenReturn(List.of(commit));
        when(documents.findDocumentIdByCommitId("abc123")).thenReturn(Optional.of(99L));
        when(dslOperationsFacade.getViewContext("alice", "draft", workspaceContext)).thenReturn(viewContext);

        ResponseEntity<Map<String, Object>> response = controller.getHistory("draft");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("currentBranch", "draft");
        assertThat(response.getBody()).containsEntry("headCommit", "abc123");
        assertThat(response.getBody()).containsEntry("viewContext", viewContext);

        verify(repositoryStateService).ensureWorkspaceState("alice");
        verify(dslOperationsFacade).getDslHistory("draft", workspaceContext);
        verify(dslOperationsFacade).getViewContext("alice", "draft", workspaceContext);
    }

    @Test
    void getHistoryFallsBackToSharedContextWhenWorkspaceProvisioningFails() throws Exception {
        ViewContext viewContext = new ViewContext("shared123", "draft", Instant.parse("2026-01-01T00:00:00Z"),
                true, false, false);

        when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        when(dslOperationsFacade.getDslHistory("draft", WorkspaceContext.SHARED)).thenReturn(List.of());
        // When SHARED context is used, getViewContext receives SHARED.username() ("system"), not the authenticated user
        when(dslOperationsFacade.getViewContext(WorkspaceContext.SHARED.username(), "draft", WorkspaceContext.SHARED)).thenReturn(viewContext);
        doThrow(new IllegalStateException("boom")).when(repositoryStateService).ensureWorkspaceState("alice");

        ResponseEntity<Map<String, Object>> response = controller.getHistory("draft");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("headCommit", "shared123");

        verify(workspaceResolver, never()).resolveCurrentContext();
        verify(dslOperationsFacade).getDslHistory("draft", WorkspaceContext.SHARED);
        verify(dslOperationsFacade).getViewContext(WorkspaceContext.SHARED.username(), "draft", WorkspaceContext.SHARED);
    }
}
