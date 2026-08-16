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

class WorkspaceResolverPortfolioContextTest {

    @AfterEach
    void clearContexts() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void compatibilityContextUsesTheSameRequestStableRepositoryAndBranch() {
        WorkspaceContextResolver delegate = mock(WorkspaceContextResolver.class);
        WorkspaceResolver resolver = new WorkspaceResolver(delegate);
        authenticate("alice");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        when(delegate.resolveRepositoryContextForUser("alice"))
                .thenReturn(RepositoryContext.workspace(
                        "repo-a", "workspace-a", "feature/current", "alice"));

        WorkspaceContext first = resolver.resolveCurrentContext();
        WorkspaceContext second = resolver.resolveCurrentContext();

        assertThat(first).isSameAs(second);
        assertThat(first.repositoryId()).isEqualTo("repo-a");
        assertThat(first.workspaceId()).isEqualTo("workspace-a");
        assertThat(first.currentBranch()).isEqualTo("feature/current");
        verify(delegate, times(0)).resolveCurrentContext();
        verify(delegate, times(1)).resolveRepositoryContextForUser("alice");
    }

    @Test
    void centralCompatibilityContextRetainsExactRepositoryAndBranch() {
        WorkspaceContextResolver delegate = mock(WorkspaceContextResolver.class);
        WorkspaceResolver resolver = new WorkspaceResolver(delegate);
        authenticate("alice");
        when(delegate.resolveRepositoryContextForUser("alice"))
                .thenReturn(RepositoryContext.centralRead("repo-a", "main", "alice"));

        WorkspaceContext context = resolver.resolveCurrentContext();

        assertThat(context.repositoryId()).isEqualTo("repo-a");
        assertThat(context.workspaceId()).isNull();
        assertThat(context.currentBranch()).isEqualTo("main");
        verify(delegate, times(0)).resolveCurrentContext();
    }

    private static void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }
}
