package com.taxonomy.architecture.controller;

import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ObligationRequestContextTest {
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final ObligationRequestContext contexts = new ObligationRequestContext(resolver);
    @Test void explicitSelectedTupleUsesAuthorizedReadResolver() {
        var selected = RepositoryContext.centralRead("repo", "main", "user");
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(selected);
        var request = new MockHttpServletRequest(); request.addHeader("X-Taxonomy-Workspace-Id", "");
        assertThat(contexts.resolve("repo", "main", "a".repeat(40), request)).isEqualTo(selected);
        verify(resolver).resolveCurrentRepositoryContextForRead();
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }
    @Test void contextMismatchFailsClosed() {
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(RepositoryContext.centralRead("other", "main", "user"));
        var request = new MockHttpServletRequest(); request.addHeader("X-Taxonomy-Workspace-Id", "");
        assertThatThrownBy(() -> contexts.resolve("repo", "main", "a".repeat(40), request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }
    @Test void missingExplicitWorkspaceFailsBeforeResolution() {
        assertThatThrownBy(() -> contexts.resolve("repo", "main", "a".repeat(40), new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verifyNoInteractions(resolver);
    }
}
