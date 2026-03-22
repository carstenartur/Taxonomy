package com.taxonomy.security.keycloak;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Custom {@link OidcUserService} that maps Keycloak OIDC claims to
 * Spring Security authorities for browser-based sessions.
 * <p>
 * After a successful OIDC login, this service:
 * <ol>
 *   <li>Loads the standard OidcUser from the UserInfo endpoint</li>
 *   <li>Extracts realm roles from the ID token or access token</li>
 *   <li>Returns an OidcUser with the correct ROLE_* authorities</li>
 * </ol>
 */
@Component
@Profile("keycloak")
public class KeycloakOidcUserService extends OidcUserService {

    /** The recognized application roles. */
    private static final Set<String> KNOWN_ROLES = Set.of("ROLE_USER", "ROLE_ARCHITECT", "ROLE_ADMIN");

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);

        // Extract realm roles from the ID token claims
        Set<GrantedAuthority> authorities = new HashSet<>(oidcUser.getAuthorities());
        authorities.addAll(extractRealmRoles(oidcUser));

        return new DefaultOidcUser(
                authorities,
                oidcUser.getIdToken(),
                oidcUser.getUserInfo(),
                "preferred_username"
        );
    }

    /**
     * Extracts realm roles from the OIDC user's claims.
     * Keycloak stores roles under {@code realm_access.roles}.
     */
    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractRealmRoles(OidcUser oidcUser) {
        Set<GrantedAuthority> roles = new HashSet<>();

        Map<String, Object> realmAccess = oidcUser.getAttribute("realm_access");
        if (realmAccess != null) {
            Object rolesObj = realmAccess.get("roles");
            if (rolesObj instanceof Collection<?> roleList) {
                roleList.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .filter(KNOWN_ROLES::contains)
                        .map(SimpleGrantedAuthority::new)
                        .forEach(roles::add);
            }
        }

        return roles;
    }
}
