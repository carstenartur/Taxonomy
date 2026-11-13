package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DslReadWorkspaceContextResolverTest {
    private final WorkspaceResolver workspace = mock(WorkspaceResolver.class);
    private final RepositoryStateService state = mock(RepositoryStateService.class);
    private final DslReadWorkspaceContextResolver resolver = new DslReadWorkspaceContextResolver(workspace,state);

    @Test
    void provisionsBeforeResolvingAndPreservesContext() {
        var context = new WorkspaceContext("alice","workspace","review");
        when(workspace.resolveCurrentContext()).thenReturn(context);
        assertThat(resolver.resolve("alice")).isSameAs(context);
        var order = inOrder(state,workspace);
        order.verify(state).ensureWorkspaceState("alice");
        order.verify(workspace).resolveCurrentContext();
        order.verifyNoMoreInteractions();
    }
    @Test
    void provisioningFailureNeverReadsTheSharedRepository() {
        doThrow(new IllegalStateException("private-backend-message")).when(state).ensureWorkspaceState("alice");
        assertThatThrownBy(() -> resolver.resolve("alice"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    var response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(response.getReason()).isEqualTo("Selected repository context unavailable");
                });
        verifyNoInteractions(workspace);
    }
    @Test
    void contextResolutionFailureNeverBecomesShared() {
        when(workspace.resolveCurrentContext()).thenThrow(new IllegalArgumentException("private-context"));
        assertThatThrownBy(() -> resolver.resolve("alice"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
    @Test
    void missingContextFailsClosed() {
        assertThatThrownBy(() -> resolver.resolve("alice"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(state).ensureWorkspaceState("alice");
    }
    @Test
    void existingAccessDenialIsNeverRelaxed() {
        doThrow(new AccessDeniedException("source access revoked")).when(state).ensureWorkspaceState("alice");
        assertThatThrownBy(() -> resolver.resolve("alice")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(workspace);
    }
    @Test
    void explicitConflictStatusIsPreserved() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "selection changed"))
                .when(state).ensureWorkspaceState("alice");
        assertThatThrownBy(() -> resolver.resolve("alice"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(workspace);
    }
}
