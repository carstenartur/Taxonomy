package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.service.WorkspaceAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

/**
 * Applies ownership checks to workspace metadata reads before controller data is
 * materialized. Shared workspaces remain visible, while guessed private IDs are
 * deliberately indistinguishable from missing IDs.
 */
@Configuration(proxyBeanMethods = false)
class WorkspaceAccessWebMvcConfiguration implements WebMvcConfigurer {

    private final WorkspaceAccessService workspaceAccessService;

    WorkspaceAccessWebMvcConfiguration(WorkspaceAccessService workspaceAccessService) {
        this.workspaceAccessService = workspaceAccessService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new WorkspaceInfoAccessInterceptor(workspaceAccessService))
                .addPathPatterns("/api/workspace/*/info");
    }

    static final class WorkspaceInfoAccessInterceptor implements HandlerInterceptor {

        private final WorkspaceAccessService workspaceAccessService;

        WorkspaceInfoAccessInterceptor(WorkspaceAccessService workspaceAccessService) {
            this.workspaceAccessService = workspaceAccessService;
        }

        @Override
        public boolean preHandle(
                HttpServletRequest request,
                HttpServletResponse response,
                Object handler) throws IOException {
            if (!(handler instanceof HandlerMethod handlerMethod)
                    || handlerMethod.getBeanType() != WorkspaceController.class
                    || !"getWorkspaceInfo".equals(handlerMethod.getMethod().getName())) {
                return true;
            }

            Principal principal = request.getUserPrincipal();
            if (principal == null) {
                // The SecurityFilterChain owns the unauthenticated response.
                return true;
            }

            String workspaceId = pathVariable(request, "id");
            if (workspaceAccessService.canReadWorkspaceMetadata(
                    workspaceId, principal.getName())) {
                return true;
            }

            response.sendError(HttpStatus.NOT_FOUND.value());
            return false;
        }

        private static String pathVariable(HttpServletRequest request, String name) {
            Object value = request.getAttribute(
                    HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            if (!(value instanceof Map<?, ?> variables)) {
                return null;
            }
            Object pathValue = variables.get(name);
            return pathValue instanceof String text ? text : null;
        }
    }
}
