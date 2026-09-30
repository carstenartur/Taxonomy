package com.taxonomy.security.model;

import com.taxonomy.backup.PrincipalId;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.Collection;
import java.util.UUID;

/** Keeps the local login name compatible while carrying the separately verified stable identity. */
public final class PrincipalUserDetails extends User implements StablePrincipal {
    private final String stableId;
    public PrincipalUserDetails(PrincipalId id, String username, String password, boolean enabled,
                                Collection<? extends GrantedAuthority> authorities) {
        super(username, password, enabled, true, true, true, authorities);
        stableId = id.value().toString();
    }
    @Override public PrincipalId principalId() { return new PrincipalId(UUID.fromString(stableId)); }
}
