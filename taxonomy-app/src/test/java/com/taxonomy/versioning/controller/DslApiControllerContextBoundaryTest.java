package com.taxonomy.versioning.controller;

import com.taxonomy.dsl.storage.DslCommit;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.versioning.service.DslOperationsFacade;
import com.taxonomy.versioning.service.HypothesisService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DslApiControllerContextBoundaryTest {

    @Mock
    private DslOperationsFacade dslOperationsFacade;

    @Mock
    private HypothesisService hypothesisService;

    @Mock
    private WorkspaceResolver workspaceResolver;

    @Mock
    private RepositoryStateService repositoryStateService;

    private DslApiController controller;

    @BeforeEach
    void setUp() {
        controller = new DslApiController(
                dslOperationsFacade,
                hypothesisService,
                workspaceResolver,
                repositoryStateService);
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
        when(dslOperationsFacade.findDocumentIdByCommitId("abc123")).thenReturn(Optional.of(99L));
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
}
