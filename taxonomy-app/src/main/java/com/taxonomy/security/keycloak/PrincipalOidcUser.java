package com.taxonomy.security.keycloak;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.model.AppPrincipal;
import com.taxonomy.security.model.StablePrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import java.util.Collection;
import java.util.UUID;

public final class PrincipalOidcUser extends DefaultOidcUser implements StablePrincipal {
    private final String stableId;
    private final String scopeKey;
    public PrincipalOidcUser(Collection<? extends GrantedAuthority> authorities, OidcUser source, AppPrincipal principal) {
        super(authorities, source.getIdToken(), source.getUserInfo(), "sub");
        stableId = principal.id().value().toString();
        scopeKey = principal.scopeKey();
    }
    @Override public String getName() { return scopeKey; }
    @Override public PrincipalId principalId() { return new PrincipalId(UUID.fromString(stableId)); }
}
