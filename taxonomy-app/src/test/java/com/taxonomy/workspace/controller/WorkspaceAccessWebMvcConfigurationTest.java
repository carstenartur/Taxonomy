package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceAccessWebMvcConfigurationTest {

    @Mock
    private UserWorkspaceRepository workspaceRepository;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private WorkspaceController workspaceController;

    private WorkspaceAccessWebMvcConfiguration.WorkspaceInfoAccessInterceptor interceptor;
    private HandlerMethod workspaceInfoHandler;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new WorkspaceAccessWebMvcConfiguration
                .WorkspaceInfoAccessInterceptor(workspaceRepository);
        Method method = WorkspaceController.class.getMethod(
                "getWorkspaceInfo", String.class);
        workspaceInfoHandler = new HandlerMethod(workspaceController, method);
    }

    @Test
    void unrelatedHandlerIsNotSubjectToWorkspaceOwnershipLookup() throws Exception {
        HandlerMethod unrelated = new HandlerMethod(
                new Object(), Object.class.getMethod("toString"));

        assertThat(interceptor.preHandle(request, response, unrelated)).isTrue();
        verify(workspaceRepository, never())
                .existsByWorkspaceIdAndUsername(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void securityFilterChainRetainsOwnershipOfAnonymousResponse() throws Exception {
        when(request.getUserPrincipal()).thenReturn(null);

        assertThat(interceptor.preHandle(
                request, response, workspaceInfoHandler)).isTrue();
        verify(response, never()).sendError(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void missingOrNonStringPathVariableFailsClosed() throws Exception {
        Principal principal = () -> "alice";
        when(request.getUserPrincipal()).thenReturn(principal);
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of());

        assertThat(interceptor.preHandle(
                request, response, workspaceInfoHandler)).isFalse();
        verify(response).sendError(HttpStatus.NOT_FOUND.value());

        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of("id", 42));
        assertThat(interceptor.preHandle(
                request, response, workspaceInfoHandler)).isFalse();
    }

    @Test
    void ownerAndSharedWorkspaceAreAllowed() throws Exception {
        Principal principal = () -> "alice";
        when(request.getUserPrincipal()).thenReturn(principal);
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of("id", "workspace-1"));
        when(workspaceRepository.existsByWorkspaceIdAndUsername(
                "workspace-1", "alice")).thenReturn(true);

        assertThat(interceptor.preHandle(
                request, response, workspaceInfoHandler)).isTrue();

        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of("id", "workspace-2"));
        when(workspaceRepository.existsByWorkspaceIdAndUsername(
                "workspace-2", "alice")).thenReturn(false);
        when(workspaceRepository.existsByWorkspaceIdAndSharedTrue("workspace-2"))
                .thenReturn(true);

        assertThat(interceptor.preHandle(
                request, response, workspaceInfoHandler)).isTrue();
    }

    @Test
    void privateForeignWorkspaceIsHidden() throws Exception {
        Principal principal = () -> "alice";
        when(request.getUserPrincipal()).thenReturn(principal);
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of("id", "workspace-bob"));
        when(workspaceRepository.existsByWorkspaceIdAndUsername(
                "workspace-bob", "alice")).thenReturn(false);
        when(workspaceRepository.existsByWorkspaceIdAndSharedTrue("workspace-bob"))
                .thenReturn(false);

        assertThat(interceptor.preHandle(
                request, response, workspaceInfoHandler)).isFalse();
        verify(response).sendError(HttpStatus.NOT_FOUND.value());
    }
}
