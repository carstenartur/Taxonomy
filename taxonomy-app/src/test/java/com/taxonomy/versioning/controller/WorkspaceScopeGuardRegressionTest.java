package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises the production resolver and its request cache, not a mocked compatibility context. */
@ExtendWith(MockitoExtension.class)
class WorkspaceScopeGuardRegressionTest {
    @Mock private WorkspaceContextResolver contexts;
    @Mock private RepositoryStateService state;
    private WorkspaceResolver resolver;
    private DslWorkspacePreResolutionInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", "unused", List.of()));
        SecurityContextHolder.setContext(security);
        resolver = new WorkspaceResolver(contexts);
        interceptor = new DslWorkspacePreResolutionInterceptor(resolver, state);
    }

    @AfterEach
    void cleanUp() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(value = RepositoryScope.class, names = "WORKSPACE", mode = EnumSource.Mode.EXCLUDE)
    void rejectsNonWorkspaceScopesEvenWhenBothWorkspaceIdsAreNull(RepositoryScope scope) {
        var context = new RepositoryContext("repo-a", null, "draft", "alice", scope);
        when(contexts.resolveRepositoryContextForUser("alice")).thenReturn(context);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .as("a %s context must never enter a workspace-scoped controller", scope)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not resolve an isolated workspace");
        assertThat(resolver.resolveCurrentContext()).isNotEqualTo(WorkspaceContext.SHARED);
        verify(state).ensureWorkspaceState("alice");
    }

    @Test
    void rejectsDefaultUserCentralFallbackWithARealRepositoryIdentity() {
        SecurityContextHolder.clearContext();
        String username = WorkspaceManager.DEFAULT_USER;
        when(contexts.resolveRepositoryContextForUser(username))
                .thenReturn(RepositoryContext.centralRead("repo-a", "draft", username));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not resolve an isolated workspace");
        verify(state).ensureWorkspaceState(username);
    }

    @Test
    void acceptsIsolatedWorkspaceAndPreservesTheRequestCachedIdentity() {
        var context = RepositoryContext.workspace("repo-a", "workspace-a", "feature-a", "alice");
        when(contexts.resolveRepositoryContextForUser("alice"))
                .thenReturn(context, RepositoryContext.centralRead("repo-b", "main", "alice"));

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(resolver.resolveCurrentRepositoryContext()).isSameAs(context);
        assertThat(resolver.resolveCurrentContext()).isEqualTo(
                new WorkspaceContext("alice", "workspace-a", "feature-a", "repo-a"));
        verify(state).ensureWorkspaceState("alice");
        verify(contexts, times(1)).resolveRepositoryContextForUser("alice");
    }
}
