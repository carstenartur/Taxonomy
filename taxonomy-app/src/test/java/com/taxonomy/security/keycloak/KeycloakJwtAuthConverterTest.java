package com.taxonomy.security.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KeycloakJwtAuthConverter}.
 */
class KeycloakJwtAuthConverterTest {

    private KeycloakJwtAuthConverter converter;

    @BeforeEach
    void setUp() {
        var identities = org.mockito.Mockito.mock(com.taxonomy.security.service.PrincipalIdentityService.class);
        org.mockito.Mockito.when(identities.oidc(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.taxonomy.security.model.AppPrincipal(
                        new com.taxonomy.backup.PrincipalId(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")), "verified-scope", true));
        converter = new KeycloakJwtAuthConverter(identities);
        converter.setRoleClaimPath("realm_access.roles");
    }

    @Test
    void extractsRealmRolesFromJwt() {
        Jwt jwt = buildJwt(
                "carsten",
                List.of("ROLE_USER", "ROLE_ADMIN")
        );

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertNotNull(token);
        Set<String> authorities = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        assertTrue(authorities.contains("ROLE_USER"));
        assertTrue(authorities.contains("ROLE_ADMIN"));
        assertEquals(2, authorities.size());
    }

    @Test
    void ignoresNonRoleClaims() {
        // Keycloak adds extra roles like "default-roles-taxonomy" or "offline_access"
        Jwt jwt = buildJwt(
                "carsten",
                List.of("ROLE_USER", "default-roles-taxonomy", "offline_access", "uma_authorization")
        );

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertNotNull(token);
        Set<String> authorities = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        assertEquals(1, authorities.size());
        assertTrue(authorities.contains("ROLE_USER"));
    }

    @Test
    void usesVerifiedPrincipalScopeInsteadOfDisplayName() {
        Jwt jwt = buildJwt("carsten", List.of("ROLE_USER"));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertEquals("verified-scope", token.getName());
    }

    @Test
    void usesVerifiedPrincipalScopeWithoutDisplayName() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256").issuer("https://idp.example")
                .subject("550e8400-e29b-41d4-a716-446655440000")
                .claim("realm_access", Map.of("roles", List.of("ROLE_USER")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertEquals("verified-scope", token.getName());
    }

    @Test
    void handlesEmptyRoles() {
        Jwt jwt = buildJwt("carsten", List.of());

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertNotNull(token);
        assertTrue(token.getAuthorities().isEmpty());
    }

    @Test
    void handlesMissingRealmAccess() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256").issuer("https://idp.example")
                .subject("test-subject")
                .claim("preferred_username", "carsten")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertNotNull(token);
        assertTrue(token.getAuthorities().isEmpty());
        assertEquals("verified-scope", token.getName());
    }

    @Test
    void extractsAllThreeKnownRoles() {
        Jwt jwt = buildJwt(
                "superadmin",
                List.of("ROLE_USER", "ROLE_ARCHITECT", "ROLE_ADMIN")
        );

        AbstractAuthenticationToken token = converter.convert(jwt);

        Set<String> authorities = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        assertEquals(Set.of("ROLE_USER", "ROLE_ARCHITECT", "ROLE_ADMIN"), authorities);
    }

    private Jwt buildJwt(String preferredUsername, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256").issuer("https://idp.example")
                .subject("test-subject-uuid")
                .claim("preferred_username", preferredUsername)
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
