package com.taxonomy.security.config;

import com.taxonomy.security.access.ApiAccessPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract for Git-authoritative decisions, independent of how request matchers are arranged. */
class GitArchitectureAuthorizationRulesContractTest {

    @ParameterizedTest
    @CsvSource({
            "POST, /api/architecture/relations/relation-1, architecture:write",
            "PUT, /api/architecture/relations/relation-1, architecture:write",
            "DELETE, /api/architecture/relations/relation-1, architecture:delete",
            "POST, /api/architecture/proposals/proposal-1, architecture:write",
            "PUT, /api/architecture/proposals/proposal-1, architecture:write",
            "DELETE, /api/architecture/proposals/proposal-1, architecture:delete"
    })
    void decisionsRequireAnArchitectAndTheCorrespondingBearerScope(String method, String path, String scope) {
        var rule = ApiAccessPolicy.resolve(method, path);
        assertThat(ApiAccessPolicy.authorize(local("ROLE_ARCHITECT"), rule)).isTrue();
        for (String role : new String[]{"ROLE_READER", "ROLE_USER", "ROLE_ADMIN"}) {
            assertThat(ApiAccessPolicy.authorize(local(role), rule)).as("%s %s for %s", method, path, role).isFalse();
            assertThat(ApiAccessPolicy.authorize(bearer(role, "SCOPE_" + scope), rule)).isFalse();
        }
        assertThat(ApiAccessPolicy.authorize(bearer("ROLE_ARCHITECT"), rule)).isFalse();
        assertThat(ApiAccessPolicy.authorize(bearer("ROLE_ARCHITECT", "SCOPE_architecture:read"), rule)).isFalse();
        assertThat(ApiAccessPolicy.authorize(bearer("ROLE_ARCHITECT", "SCOPE_" + scope), rule)).isTrue();
        assertThat(ApiAccessPolicy.authorize(null, rule)).isFalse();
    }

    @Test
    void readAndWriteScopesCannotAuthorizeDedicatedDecisionDeletion() {
        var token = bearer("ROLE_ARCHITECT", "SCOPE_architecture:read", "SCOPE_architecture:write");
        for (String path : new String[]{"/api/architecture/relations/relation-1", "/api/architecture/proposals/proposal-1"}) {
            assertThat(ApiAccessPolicy.authorize(token, ApiAccessPolicy.resolve("DELETE", path))).isFalse();
        }
    }

    @Test
    void unknownWritesRemainDeniedEvenForAnArchitectAdministrator() {
        var actor = local("ROLE_ARCHITECT", "ROLE_ADMIN");
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            assertThat(ApiAccessPolicy.authorize(actor, ApiAccessPolicy.resolve(method, "/api/unclassified-decisions")))
                    .as("unclassified %s", method).isFalse();
        }
    }

    private static UsernamePasswordAuthenticationToken local(String... authorities) {
        return UsernamePasswordAuthenticationToken.authenticated("architect", "unused", authorities(authorities));
    }

    private static JwtAuthenticationToken bearer(String... authorities) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("test-token").header("alg", "none")
                .subject("architect").build(), authorities(authorities));
    }

    private static java.util.List<SimpleGrantedAuthority> authorities(String... authorities) {
        return Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
    }
}
