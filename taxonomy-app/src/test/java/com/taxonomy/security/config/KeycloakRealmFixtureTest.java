package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmFixtureTest {
    @Test void addingApiScopesPreservesBuiltInIdentityScopesAndAllowsRestrictedTokens() throws Exception {
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (!java.nio.file.Files.exists(root.resolve("keycloak/taxonomy-realm.json"))) root = root.getParent();
        var mapper = JsonMapper.builder().build();
        var production = mapper.readTree(java.nio.file.Files.readString(root.resolve("keycloak/taxonomy-realm.json")));
        try (var input = getClass().getResourceAsStream("/keycloak/taxonomy-test-realm.json")) {
            for (var realm : java.util.List.of(production, mapper.readTree(input))) {
                // Keycloak skips its built-in client scopes when an import supplies custom scopes
                // unless this import option is enabled. In particular, basic supplies the subject.
                assertThat(realm.path("attributes").path("CreateDefaultClientScopes").asText()).isEqualTo("true");
                for (var client : realm.path("clients")) {
                    var defaults = new ArrayList<String>();
                    client.path("defaultClientScopes").forEach(scope -> defaults.add(scope.asText()));
                    assertThat(defaults).contains("basic").doesNotContain("architecture:read", "architecture:write");
                    var optional = new ArrayList<String>();
                    client.path("optionalClientScopes").forEach(scope -> optional.add(scope.asText()));
                    assertThat(optional).contains("architecture:read", "architecture:write", "analysis:run");
                }
            }
        }
    }

    @Test void allClientsKeepTheSubjectScopeEvenWhenRoleClaimsAreIntentionallyMissing() throws Exception {
        try (var input = getClass().getResourceAsStream("/keycloak/taxonomy-test-realm.json")) {
            assertThat(input).isNotNull();
            var realm = JsonMapper.builder().build().readTree(input);
            assertThat(realm.path("clients").size()).isEqualTo(3);
            for (var client : realm.path("clients")) {
                var scopes = new ArrayList<String>();
                client.path("defaultClientScopes").forEach(scope -> scopes.add(scope.asText()));
                assertThat(scopes).as("subject scope for %s", client.path("clientId").asText()).contains("basic");
            }
        }
    }
}
