package com.taxonomy.security.keycloak;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link KeycloakLogoutHandler}. */
class KeycloakLogoutHandlerTest {

    @Test
    void redirectsToKeycloakLogoutThenFreshOidcChallenge() throws Exception {
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
                Map.of("sub", "user-uuid",
                        "iss", "http://localhost:8180/realms/taxonomy"));
        when(oidcUser.getIdToken()).thenReturn(idToken);

        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(oidcUser);

        handler.onLogoutSuccess(request, response, auth);

        verify(response).sendRedirect(argThat(url -> {
            String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
            assertTrue(decoded.startsWith(
                    "http://localhost:8180/realms/taxonomy/protocol/openid-connect/logout"));
            assertTrue(decoded.contains("id_token_hint=test-id-token-value"));
            assertTrue(decoded.contains("post_logout_redirect_uri="
                    + "http://localhost:8080/oauth2/authorization/keycloak"));
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
