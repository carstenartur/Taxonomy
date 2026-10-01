package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmFixtureTest {
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
