package com.taxonomy.security.access;

import com.taxonomy.workspace.model.RepositoryRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryClaimGrantProviderTest {
    private final RepositoryClaimGrantProvider provider = new RepositoryClaimGrantProvider();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedJwtGrantsExactRepositoryRoleToCurrentActor() {
        authenticate(jwt(Map.of("repo-a", "READER", "repo-b", "OWNER")));

        assertEquals(RepositoryRole.READER, provider.findRole("repo-a", "alice").orElse(null));
        assertEquals(RepositoryRole.OWNER, provider.findRole("repo-b", "alice").orElse(null));
        assertTrue(provider.findRole("repo-c", "alice").isEmpty());
        assertTrue(provider.findRole("repo-a ", "alice").isEmpty());
    }

    @Test
    void grantsCannotBeBorrowedByAnotherOrNormalizedActor() {
        authenticate(jwt(Map.of("repo-a", "OWNER")));

        assertTrue(provider.findRole("repo-a", "bob").isEmpty());
        assertTrue(provider.findRole("repo-a", " alice ").isEmpty());
        assertTrue(provider.findRole("repo-a", null).isEmpty());
    }

    @Test
    void authenticatedOidcSessionUsesItsValidatedClaims() {
        Instant now = Instant.now();
        var token = new OidcIdToken("id-token", now, now.plusSeconds(60),
                Map.of("sub", "alice", "taxonomy_repository_roles", Map.of("repo-a", "MAINTAINER")));
        var user = new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_READER"), token);
        authenticate(new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak"));

        assertEquals(RepositoryRole.MAINTAINER, provider.findRole("repo-a", "alice").orElse(null));
        assertTrue(provider.findRole("repo-a", "bob").isEmpty());
    }

    @Test
    void localAuthenticationMapPrincipalCannotSupplyGrants() {
        authenticate(UsernamePasswordAuthenticationToken.authenticated(
                Map.of("sub", "alice", "taxonomy_repository_roles", Map.of("repo-a", "OWNER")),
                "unused", List.of()));

        assertTrue(provider.findRole("repo-a", SecurityContextHolder.getContext()
                .getAuthentication().getName()).isEmpty());
    }

    @Test
    void unauthenticatedJwtCannotSupplyGrants() {
        authenticate(new JwtAuthenticationToken(token(Map.of("repo-a", "OWNER"))));

        assertTrue(provider.findRole("repo-a", "alice").isEmpty());
    }

    @Test
    void absentAuthenticationOrClaimGrantsNothing() {
        assertTrue(provider.findRole("repo-a", "alice").isEmpty());
        authenticate(new JwtAuthenticationToken(Jwt.withTokenValue("token").header("alg", "RS256")
                .subject("alice").build(), List.of(), "alice"));
        assertTrue(provider.findRole("repo-a", "alice").isEmpty());
    }

    @Test
    void malformedClaimsFailClosedIncludingMalformedEntriesAlongsideValidEntries() {
        for (Object malformed : List.of("OWNER", List.of("OWNER"),
                Map.of("repo-a", "reader"), Map.of("repo-a", "ROLE_OWNER"),
                Map.of("repo-a", List.of("OWNER")), Map.of(1, "OWNER"),
                Map.of("repo-a", "OWNER", "repo-b", "unknown"),
                Map.of("repo-a", "OWNER", " repo-b ", "READER"))) {
            authenticate(jwt(malformed));
            assertTrue(provider.findRole("repo-a", "alice").isEmpty(), malformed.toString());
        }
    }

    @Test
    void jwtGrantUsesAuthenticationNameInsteadOfSubject() {
        authenticate(new JwtAuthenticationToken(token(Map.of("repo-a", "CONTRIBUTOR")),
                List.of(), "principal:stable-id"));

        assertEquals(RepositoryRole.CONTRIBUTOR,
                provider.findRole("repo-a", "principal:stable-id").orElse(null));
        assertTrue(provider.findRole("repo-a", "alice").isEmpty());
    }

    private static JwtAuthenticationToken jwt(Object roles) {
        return new JwtAuthenticationToken(token(roles), List.of(), "alice");
    }

    private static Jwt token(Object roles) {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("alice")
                .claim("taxonomy_repository_roles", roles).build();
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
