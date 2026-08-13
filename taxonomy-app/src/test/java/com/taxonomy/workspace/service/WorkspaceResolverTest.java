package com.taxonomy.workspace.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceResolverTest {

    @AfterEach
    void clearRequestAndSecurityContext() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void repositoryContextIsResolvedOnceAndRemainsStableForTheRequest() {
        WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
        WorkspaceResolver resolver = new WorkspaceResolver(contextResolver);
        RepositoryContext expected = RepositoryContext.workspace(
                "repo-a", "workspace-a", "draft", "alice");
        authenticate("alice");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        when(contextResolver.resolveRepositoryContextForUser("alice"))
                .thenReturn(expected);

        RepositoryContext first = resolver.resolveCurrentRepositoryContext();
        RepositoryContext second = resolver.resolveCurrentRepositoryContext();

        assertThat(first).isSameAs(expected);
        assertThat(second).isSameAs(expected);
        verify(contextResolver, times(1)).resolveRepositoryContextForUser("alice");
    }

    @Test
    void separateRequestsCannotReuseARepositoryContext() {
        WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
        WorkspaceResolver resolver = new WorkspaceResolver(contextResolver);
        RepositoryContext firstContext = RepositoryContext.centralRead(
                "repo-a", "main", "alice");
        RepositoryContext secondContext = RepositoryContext.centralRead(
                "repo-b", "main", "alice");
        authenticate("alice");
        when(contextResolver.resolveRepositoryContextForUser("alice"))
                .thenReturn(firstContext, secondContext);

        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThat(resolver.resolveCurrentRepositoryContext()).isSameAs(firstContext);

        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThat(resolver.resolveCurrentRepositoryContext()).isSameAs(secondContext);

        verify(contextResolver, times(2)).resolveRepositoryContextForUser("alice");
    }

    @Test
    void unauthenticatedResolutionUsesTheConfiguredDefaultUser() {
        WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
        WorkspaceResolver resolver = new WorkspaceResolver(contextResolver);
        RepositoryContext expected = RepositoryContext.centralRead(
                "primary", "draft", WorkspaceManager.DEFAULT_USER);
        when(contextResolver.resolveRepositoryContextForUser(WorkspaceManager.DEFAULT_USER))
                .thenReturn(expected);

        assertThat(resolver.resolveCurrentRepositoryContext()).isSameAs(expected);
    }

    private static void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }
}
