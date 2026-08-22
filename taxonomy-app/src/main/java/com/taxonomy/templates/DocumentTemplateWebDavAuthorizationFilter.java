package com.taxonomy.templates;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps the virtual template collection readable for authenticated users while
 * limiting mutations to administrators who can also access the template workspace.
 *
 * <p>The filter runs after Spring Security's servlet filter, so servlet role checks
 * use the already authenticated principal in both form-login and Keycloak modes.</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class DocumentTemplateWebDavAuthorizationFilter extends OncePerRequestFilter {

    private static final Set<String> WRITE_METHODS = Set.of("PUT", "LOCK", "UNLOCK");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        String path = contextPath == null || contextPath.isEmpty()
                ? requestUri
                : requestUri.substring(contextPath.length());
        return !path.equals("/dav/templates") && !path.startsWith("/dav/templates/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (WRITE_METHODS.contains(method) && !request.isUserInRole("ADMIN")) {
            response.sendError(
                    HttpServletResponse.SC_FORBIDDEN,
                    "Administrator role is required to modify document templates");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
