package com.taxonomy.security.config;

import com.taxonomy.security.service.PasswordChangeService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordChangeRequiredFilterTest {

    private static final String USERNAME = "restricted-user";

    @Mock
    private PasswordChangeService passwordChangeService;
    @Mock
    private FilterChain filterChain;

    private PasswordChangeRequiredFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new PasswordChangeRequiredFilter(passwordChangeService, true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void prefixedLifecycleAndStaticPathsRemainExempt() {
        for (String path : List.of(
                "/login",
                "/login/oauth2/code/keycloak",
                "/logout",
                "/change-password",
                "/api/account/change-password",
                "/error",
                "/css/taxonomy.css",
                "/js/taxonomy-i18n.js",
                "/images/taxonomy.svg",
                "/webjars/bootstrap/5.3.8/dist/css/bootstrap.min.css")) {
            MockHttpServletRequest request = prefixedRequest("GET", path);

            assertThat(filter.shouldNotFilter(request))
                    .as("application path %s", path)
                    .isTrue();
            assertThat(PasswordChangeRequiredFilter.applicationPath(request))
                    .isEqualTo(path);
        }
    }

    @Test
    void prefixedProtectedPathStillRequiresEnforcement() {
        MockHttpServletRequest request =
                prefixedRequest("GET", "/api/taxonomy");

        assertThat(filter.shouldNotFilter(request)).isFalse();
        assertThat(PasswordChangeRequiredFilter.applicationPath(request))
                .isEqualTo("/api/taxonomy");
    }

    @Test
    void prefixedApiResponseIsContextAwareAndNotCacheable()
            throws Exception {
        authenticateRestrictedUser();
        MockHttpServletRequest request =
                prefixedRequest("GET", "/api/taxonomy");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(428);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"error\":\"PASSWORD_CHANGE_REQUIRED\"")
                .contains("\"changePasswordEndpoint\":"
                        + "\"/taxonomy/api/account/change-password\"");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void prefixedBrowserRedirectStaysInsideContext() throws Exception {
        authenticateRestrictedUser();
        MockHttpServletRequest request = prefixedRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .isEqualTo("/taxonomy/change-password");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void rootDeploymentRetainsExistingExternalPaths() throws Exception {
        authenticateRestrictedUser();
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/taxonomy");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(428);
        assertThat(response.getContentAsString())
                .contains("\"changePasswordEndpoint\":"
                        + "\"/api/account/change-password\"");
    }

    private void authenticateRestrictedUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        USERNAME,
                        "unused",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(passwordChangeService.isPasswordChangeRequired(USERNAME))
                .thenReturn(true);
    }

    private static MockHttpServletRequest prefixedRequest(
            String method,
            String applicationPath) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                method, "/taxonomy" + applicationPath);
        request.setContextPath("/taxonomy");
        request.setServletPath(applicationPath);
        return request;
    }
}
