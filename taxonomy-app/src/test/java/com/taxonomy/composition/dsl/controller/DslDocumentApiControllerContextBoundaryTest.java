package com.taxonomy.composition.dsl.controller;

import com.taxonomy.composition.dsl.service.DslDocumentOperationsFacade;
import com.taxonomy.versioning.controller.DslReadWorkspaceContextResolver;

import com.taxonomy.workspace.storage.DslCommit;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DslDocumentApiControllerContextBoundaryTest {
    private final com.taxonomy.composition.dsl.service.ProjectDslSource projects =
            org.mockito.Mockito.mock(com.taxonomy.composition.dsl.service.ProjectDslSource.class);


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
                new DslReadWorkspaceContextResolver(workspaceResolver, repositoryStateService), projects);
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
    void getHistoryStopsBeforeGitReadIfWorkspaceProvisioningFails() {
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        doThrow(new IllegalStateException("private-workspace-detail"))
                .when(repositoryStateService).ensureWorkspaceState("alice");

        assertThatThrownBy(() -> controller.getHistory("draft"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    var response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(response.getReason()).doesNotContain("private");
                });

        verify(workspaceResolver, never()).resolveCurrentContext();
        verifyNoInteractions(dslOperationsFacade, documents, projects);
    }
}
