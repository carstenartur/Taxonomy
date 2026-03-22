package com.taxonomy.security.keycloak;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KeycloakLogoutHandler}.
 */
class KeycloakLogoutHandlerTest {

    @Test
    void redirectsToKeycloakLogoutEndpoint() throws Exception {
        KeycloakLogoutHandler handler = new KeycloakLogoutHandler();
        handler.setIssuerUri("http://localhost:8180/realms/taxonomy");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.setContextPath("");

        HttpServletResponse response = mock(HttpServletResponse.class);

        OidcUser oidcUser = mock(OidcUser.class);
        OidcIdToken idToken = new OidcIdToken(
                "test-id-token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("sub", "user-uuid", "iss", "http://localhost:8180/realms/taxonomy")
        );
        when(oidcUser.getIdToken()).thenReturn(idToken);

        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(oidcUser);

        handler.onLogoutSuccess(request, response, auth);

        verify(response).sendRedirect(argThat(url -> {
            assertTrue(url.startsWith("http://localhost:8180/realms/taxonomy/protocol/openid-connect/logout"));
            assertTrue(url.contains("id_token_hint=test-id-token-value"));
            assertTrue(url.contains("post_logout_redirect_uri="));
            return true;
        }));
    }

    @Test
    void invalidatesSessionOnLogout() throws Exception {
        KeycloakLogoutHandler handler = new KeycloakLogoutHandler();
        handler.setIssuerUri("http://localhost:8180/realms/taxonomy");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.setContextPath("");

        HttpSession session = mock(HttpSession.class);
        request.setSession(session);

        HttpServletResponse response = mock(HttpServletResponse.class);

        handler.onLogoutSuccess(request, response, null);

        verify(session).invalidate();
    }
}
