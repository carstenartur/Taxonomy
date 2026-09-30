package com.taxonomy.security.keycloak;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.model.AppPrincipal;
import com.taxonomy.security.model.StablePrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.Collection;
import java.util.UUID;

public final class PrincipalJwtAuthenticationToken extends JwtAuthenticationToken implements StablePrincipal {
    private final String stableId;
    public PrincipalJwtAuthenticationToken(Jwt jwt, Collection<? extends GrantedAuthority> authorities, AppPrincipal principal) {
        super(jwt, authorities, principal.scopeKey());
        stableId = principal.id().value().toString();
    }
    @Override public PrincipalId principalId() { return new PrincipalId(UUID.fromString(stableId)); }
}
