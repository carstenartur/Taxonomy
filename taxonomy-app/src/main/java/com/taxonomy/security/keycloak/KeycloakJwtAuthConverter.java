package com.taxonomy.security.keycloak;

import com.taxonomy.security.service.PrincipalIdentityService;
import org.springframework.security.authentication.BadCredentialsException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Converts Keycloak JWT claims to Spring Security authorities.
 * <p>
 * Keycloak stores realm roles under:
 * <pre>
 *   { "realm_access": { "roles": ["ROLE_USER", "ROLE_ADMIN", ...] } }
 * </pre>
 * <p>
 * This converter extracts those roles and maps them to Spring
 * {@link GrantedAuthority} instances, preserving the existing
 * four-role model (ROLE_READER, ROLE_USER, ROLE_ARCHITECT, ROLE_ADMIN).
 * Standard OAuth scopes are retained as SCOPE_* authorities.
 * Stable mode resolves issuer and subject to a persisted scope key. The default
 * legacy mode preserves existing username-based ownership until verified migration
 * is available; its tokens cannot be used as stable backup identities.
 */
@Component
@Profile("keycloak")
public class KeycloakJwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    /** The recognized application roles. */
    private static final Set<String> KNOWN_ROLES = Set.of("ROLE_READER", "ROLE_USER", "ROLE_ARCHITECT", "ROLE_ADMIN");
    private final JwtGrantedAuthoritiesConverter scopeAuthorities = new JwtGrantedAuthoritiesConverter();
    private final PrincipalIdentityService identities;
    private final KeycloakPrincipalMode mode;

    public KeycloakJwtAuthConverter(PrincipalIdentityService identities, KeycloakPrincipalMode mode) {
        this.identities = identities;
        this.mode = java.util.Objects.requireNonNull(mode);
    }

    @Value("${taxonomy.keycloak.role-claim-path:realm_access.roles}")
    private String roleClaimPath = "realm_access.roles";

    /** Visible for testing — sets the role claim path. */
    void setRoleClaimPath(String roleClaimPath) {
        this.roleClaimPath = roleClaimPath;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractAuthorities(jwt);

        if (mode == KeycloakPrincipalMode.LEGACY) {
            String username = jwt.getClaimAsString("preferred_username");
            if (username == null || username.isBlank()) username = jwt.getSubject();
            return new JwtAuthenticationToken(jwt, authorities, username);
        }
        if (jwt.getIssuer() == null || jwt.getSubject() == null || jwt.getSubject().isBlank())
            throw new BadCredentialsException("Verified issuer and subject are required");
        var principal = identities.oidc(jwt.getIssuer().toString(), jwt.getSubject());
        return new PrincipalJwtAuthenticationToken(jwt, authorities, principal);
    }

    /** Extracts explicit known roles alongside standard scope/scp authorities. */
    Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(scopeAuthorities.convert(jwt));
        Object current = jwt.getClaims();
        for (String part : roleClaimPath.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return authorities;
            }
        }
        if (current instanceof Collection<?> roles) {
            roles.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(KNOWN_ROLES::contains)
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authorities::add);
        }
        return authorities;
    }
}
