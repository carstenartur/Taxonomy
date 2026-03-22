package com.taxonomy.security.keycloak;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.io.IOException;
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
        setIssuerUri(handler, "http://localhost:8180/realms/taxonomy");

        HttpServletRequest request = mockRequest();
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
        setIssuerUri(handler, "http://localhost:8180/realms/taxonomy");

        HttpSession session = mock(HttpSession.class);
        HttpServletRequest request = mockRequest();
        when(request.getSession(false)).thenReturn(session);
        when(request.getSession()).thenReturn(session);

        HttpServletResponse response = mock(HttpServletResponse.class);

        handler.onLogoutSuccess(request, response, null);

        verify(session).invalidate();
    }

    private HttpServletRequest mockRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getContextPath()).thenReturn("");
        when(request.getSession(false)).thenReturn(null);
        return request;
    }

    private void setIssuerUri(KeycloakLogoutHandler handler, String uri) {
        try {
            var field = KeycloakLogoutHandler.class.getDeclaredField("issuerUri");
            field.setAccessible(true);
            field.set(handler, uri);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
