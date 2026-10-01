package com.taxonomy.security.keycloak;

import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.security.service.PrincipalIdentityService;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class KeycloakPrincipalIsolationTest {
    PrincipalIdentityService identities;

    @BeforeEach void database() {
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:oidc-" + UUID.randomUUID());
        database.setUser("sa");
        PrincipalSchemaMigration.migrate(database);
        identities = new PrincipalIdentityService(database);
    }

    @Test void equalDisplayNamesAtDifferentIssuersNeverShareAScope() {
        var converter = new KeycloakJwtAuthConverter(identities);
        converter.setRoleClaimPath("realm_access.roles");
        String first = converter.convert(jwt("https://first.example", "person-1", "alice")).getName();
        String other = converter.convert(jwt("https://other.example", "person-1", "alice")).getName();
        assertThat(first).isNotEqualTo("alice").isNotEqualTo(other);
        assertThat(converter.convert(jwt("https://first.example", "person-1", "renamed")).getName()).isEqualTo(first);
    }

    @Test void browserAndBearerLoginUseTheSameVerifiedIdentity() {
        String issuer = "https://idp.example";
        var converter = new KeycloakJwtAuthConverter(identities);
        converter.setRoleClaimPath("realm_access.roles");
        var registration = ClientRegistration.withRegistrationId("test").clientId("taxonomy")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("https://app.example/login")
                .authorizationUri(issuer + "/auth").tokenUri(issuer + "/token").jwkSetUri(issuer + "/keys")
                .issuerUri(issuer).scope("openid").userNameAttributeName("preferred_username").build();
        Instant now = Instant.now();
        var token = new OidcIdToken("verified-token", now, now.plusSeconds(300),
                Map.of("iss", issuer, "sub", "person-1", "preferred_username", "alice"));
        var request = new OidcUserRequest(registration,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access", now, now.plusSeconds(300)), token);
        var user = new KeycloakOidcUserService(identities).loadUser(request);
        assertThat(user.getName()).isEqualTo(converter.convert(jwt(issuer, "person-1", "alice")).getName());
        assertThat(user.getName()).isNotEqualTo("alice");
        assertThat(user.getPreferredUsername()).isEqualTo("alice");
    }

    @Test void missingProviderIdentityIsRejectedInsteadOfUsingDisplayName() {
        var converter = new KeycloakJwtAuthConverter(identities);
        converter.setRoleClaimPath("realm_access.roles");
        assertThatThrownBy(() -> converter.convert(Jwt.withTokenValue("token").header("alg", "RS256")
                .subject("person-1").claim("preferred_username", "alice").build()))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
    }

    @Test void missingSubjectIsRejectedEvenWithAValidIssuerAndDisplayName() {
        var converter = new KeycloakJwtAuthConverter(identities);
        converter.setRoleClaimPath("realm_access.roles");
        assertThatThrownBy(() -> converter.convert(Jwt.withTokenValue("token").header("alg", "RS256")
                .issuer("https://idp.example").claim("preferred_username", "alice").build()))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
    }

    @Test void opaqueLoginScopeCanProvisionTheExistingUsernameBasedGitWorkspace() throws Exception {
        var converter = new KeycloakJwtAuthConverter(identities);
        converter.setRoleClaimPath("realm_access.roles");
        String scope = converter.convert(jwt("https://idp.example", "person-1", "alice")).getName();
        try (var repository = new com.taxonomy.workspace.storage.DslGitRepository()) {
            String initial = repository.commitDsl("draft", "element E { title: \"Initial\" }", "system", "initial");
            String branch = scope + "/workspace/owned";
            // The in-memory ref store accepts some names that native Git cannot clone.
            assertThat(org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + branch)).isTrue();
            repository.createBranchAtCommit(branch, initial);
            assertThat(repository.getHeadCommit(branch)).isEqualTo(initial);
        }
    }

    private Jwt jwt(String issuer, String subject, String displayName) {
        return Jwt.withTokenValue("verified-token").header("alg", "RS256").issuer(issuer).subject(subject)
                .claim("preferred_username", displayName).build();
    }
}
