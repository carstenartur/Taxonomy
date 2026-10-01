package com.taxonomy.security.keycloak;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("keycloak")
public class KeycloakPrincipalConfiguration {
    @Bean
    KeycloakPrincipalMode keycloakPrincipalMode(
            @Value("${taxonomy.keycloak.principal-mode:LEGACY}") KeycloakPrincipalMode mode,
            @Value("${taxonomy.backup.enabled:false}") boolean backupEnabled) {
        if (backupEnabled && mode != KeycloakPrincipalMode.STABLE) {
            throw new IllegalStateException("Portable backups require taxonomy.keycloak.principal-mode=STABLE; "
                    + "existing OIDC ownership must be explicitly migrated before enabling stable identities");
        }
        return mode;
    }
}
