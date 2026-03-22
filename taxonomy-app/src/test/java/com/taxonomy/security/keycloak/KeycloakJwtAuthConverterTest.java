package com.taxonomy.security.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
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
        converter = new KeycloakJwtAuthConverter();
        // Set the default claim path via reflection since @Value is not processed in unit tests
        try {
            var field = KeycloakJwtAuthConverter.class.getDeclaredField("roleClaimPath");
            field.setAccessible(true);
            field.set(converter, "realm_access.roles");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
    void usesPreferredUsernameAsPrincipalName() {
        Jwt jwt = buildJwt("carsten", List.of("ROLE_USER"));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertEquals("carsten", token.getName());
    }

    @Test
    void fallsBackToSubjectIfNoPreferredUsername() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("550e8400-e29b-41d4-a716-446655440000")
                .claim("realm_access", Map.of("roles", List.of("ROLE_USER")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertEquals("550e8400-e29b-41d4-a716-446655440000", token.getName());
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
                .header("alg", "RS256")
                .subject("test-subject")
                .claim("preferred_username", "carsten")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertNotNull(token);
        assertTrue(token.getAuthorities().isEmpty());
        assertEquals("carsten", token.getName());
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
                .header("alg", "RS256")
                .subject("test-subject-uuid")
                .claim("preferred_username", preferredUsername)
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
