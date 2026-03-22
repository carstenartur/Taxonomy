package com.taxonomy.security.keycloak;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * RP-Initiated Logout: after Spring session invalidation, redirect the user
 * to Keycloak's logout endpoint so the SSO session is terminated across all
 * applications.
 * <p>
 * Uses {@link ServletUriComponentsBuilder} to correctly derive the
 * post-logout redirect URI from the current request, which respects
 * {@code X-Forwarded-*} / {@code Forwarded} headers when
 * {@code server.forward-headers-strategy=framework} is enabled.
 * <p>
 * Keycloak end_session_endpoint:
 * <pre>
 *   {issuer}/protocol/openid-connect/logout?
 *     id_token_hint={id_token}&amp;
 *     post_logout_redirect_uri={app_url}
 * </pre>
 */
@Component
@Profile("keycloak")
public class KeycloakLogoutHandler implements LogoutSuccessHandler {

    @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri:http://localhost:8180/realms/taxonomy}")
    private String issuerUri;

    /** Visible for testing — sets the issuer URI. */
    void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request,
                                HttpServletResponse response,
                                Authentication authentication) throws IOException, ServletException {
        // Invalidate the HTTP session
        if (request.getSession(false) != null) {
            request.getSession().invalidate();
        }

        // Build the Keycloak end_session_endpoint URL
        String logoutUrl = issuerUri + "/protocol/openid-connect/logout";

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(logoutUrl);

        // Include id_token_hint for Keycloak to identify the session
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser) {
            String idToken = oidcUser.getIdToken().getTokenValue();
            builder.queryParam("id_token_hint", idToken);
        }

        // Derive the post-logout redirect URI from the current request.
        // ServletUriComponentsBuilder respects X-Forwarded-* headers behind a
        // reverse proxy (when server.forward-headers-strategy=framework is set).
        String postLogoutRedirectUri = ServletUriComponentsBuilder.fromRequest(request)
                .replacePath(request.getContextPath() + "/")
                .replaceQuery(null)
                .fragment(null)
                .build()
                .toUriString();
        builder.queryParam("post_logout_redirect_uri", postLogoutRedirectUri);

        response.sendRedirect(builder.build().toUriString());
    }
}
