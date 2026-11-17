package com.taxonomy.security.model;

import com.taxonomy.backup.PrincipalId;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.UUID;

/** Keeps the login name as display data while keying Spring's session registry by the verified principal. */
public final class PrincipalUserDetails implements UserDetails, CredentialsContainer, StablePrincipal {
    private static final long serialVersionUID = 1L;
    private final User login;
    private final UUID stableId;

    public PrincipalUserDetails(PrincipalId id, String username, String password, boolean enabled,
                                Collection<? extends GrantedAuthority> authorities) {
        login = new User(username, password, enabled, true, true, true, authorities);
        stableId = id.value();
    }

    @Override public PrincipalId principalId() { return new PrincipalId(stableId); }
    @Override public String getUsername() { return login.getUsername(); }
    @Override public String getPassword() { return login.getPassword(); }
    @Override public boolean isEnabled() { return login.isEnabled(); }
    @Override public Collection<GrantedAuthority> getAuthorities() { return login.getAuthorities(); }
    @Override public void eraseCredentials() { login.eraseCredentials(); }

    @Override
    public boolean equals(Object other) {
        return other instanceof PrincipalUserDetails principal && stableId.equals(principal.stableId);
    }

    @Override public int hashCode() { return stableId.hashCode(); }
}
