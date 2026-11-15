package com.taxonomy.security.access;

import com.taxonomy.workspace.service.WorkspaceBranchDeletion;
import com.taxonomy.workspace.service.WorkspaceFetchAuthority;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Recheck the same application permission and verified actor at the destructive effect. */
@Component
public class VariantDeletionAuthority implements WorkspaceBranchDeletion.Actor {
    private final WorkspaceFetchAuthority identities;

    public VariantDeletionAuthority(WorkspaceFetchAuthority identities) { this.identities = identities; }

    @Override public String require(String username) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!ApiAccessPolicy.hasPermission(auth, ApiPermission.ARCHITECTURE_DELETE)) throw denied();
        if (auth instanceof JwtAuthenticationToken jwt) requireLive(jwt.getToken().getExpiresAt());
        if (auth instanceof OAuth2AuthenticationToken && auth.getPrincipal() instanceof OidcUser oidc) {
            requireLive(oidc.getIdToken().getExpiresAt());
        }
        return identities.currentActor(username);
    }

    private static void requireLive(Instant expiry) {
        if (expiry == null || !expiry.isAfter(Instant.now())) throw denied();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Current variant delete permission required");
    }
}
