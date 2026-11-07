package com.taxonomy.security.access;

import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.service.RepositoryRoleGrantProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Reads repository grants from authenticated identity-provider claims. */
@Component
public class RepositoryClaimGrantProvider implements RepositoryRoleGrantProvider {
    private static final String CLAIM = "taxonomy_repository_roles";

    @Override
    public Optional<RepositoryRole> findRole(String repositoryId, String actor) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || actor == null || !actor.equals(authentication.getName())
                || repositoryId == null || repositoryId.isBlank()) {
            return Optional.empty();
        }

        // These authentication types are created after JWT/OIDC validation by Spring Security.
        // In particular, local authentication principals and request parameters are not claims.
        Object claim;
        if (authentication instanceof JwtAuthenticationToken jwt) {
            claim = jwt.getToken().getClaims().get(CLAIM);
        } else if (authentication instanceof OAuth2AuthenticationToken oauth2
                && oauth2.getPrincipal() instanceof OidcUser oidc) {
            claim = oidc.getClaims().get(CLAIM);
        } else {
            return Optional.empty();
        }
        if (!(claim instanceof Map<?, ?> roles)) {
            return Optional.empty();
        }

        // Reject the whole malformed mapping rather than partially trusting its entries.
        for (var entry : roles.entrySet()) {
            if (!(entry.getKey() instanceof String id) || id.isBlank() || !id.equals(id.strip())
                    || !(entry.getValue() instanceof String role) || parseRole(role).isEmpty()) {
                return Optional.empty();
            }
        }
        Object role = roles.get(repositoryId);
        return role instanceof String value ? parseRole(value) : Optional.empty();
    }

    private static Optional<RepositoryRole> parseRole(String value) {
        try {
            return Optional.of(RepositoryRole.valueOf(value));
        } catch (IllegalArgumentException invalidRole) {
            return Optional.empty();
        }
    }
}
