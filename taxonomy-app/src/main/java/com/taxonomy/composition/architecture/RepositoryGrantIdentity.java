package com.taxonomy.composition.architecture;

import com.taxonomy.security.keycloak.KeycloakPrincipalMode;
import com.taxonomy.security.model.StablePrincipal;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.workspace.service.RepositoryMembershipIdentity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Reads the existing registry. A legacy login label never becomes a principal UUID. */
@Component
public final class RepositoryGrantIdentity implements RepositoryMembershipIdentity {
    private final PrincipalIdentityService identities;
    private final Optional<KeycloakPrincipalMode> mode;

    public RepositoryGrantIdentity(PrincipalIdentityService identities, Optional<KeycloakPrincipalMode> mode) {
        this.identities = identities;
        this.mode = mode;
    }

    @Override public Optional<UUID> currentActor(String scope) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth instanceof StablePrincipal || auth.getPrincipal() instanceof StablePrincipal)) {
            return Optional.empty();
        }
        if (!scope.equals(auth.getName())) return Optional.empty();
        return Optional.of(identities.requireCurrent(auth).value());
    }

    @Override public Optional<UUID> activeHolder(String scope) {
        if (mode.orElse(null) == KeycloakPrincipalMode.LEGACY) return Optional.empty();
        return identities.findActiveForScope(scope).map(principal -> principal.id().value());
    }
}
