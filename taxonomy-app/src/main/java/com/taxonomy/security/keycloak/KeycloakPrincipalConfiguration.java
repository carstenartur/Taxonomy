package com.taxonomy.security.keycloak;

import com.taxonomy.security.service.LegacyOwnerMigrationReadiness;
import com.taxonomy.security.service.PrincipalIdentityService;
import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("keycloak")
public class KeycloakPrincipalConfiguration {
    @Bean
    KeycloakPrincipalMode keycloakPrincipalMode(
            @Value("${taxonomy.keycloak.principal-mode:LEGACY}") KeycloakPrincipalMode mode,
            @Value("${taxonomy.backup.enabled:false}") boolean backupEnabled,
            PrincipalIdentityService initializedIdentities, DataSource database) {
        if (backupEnabled && mode != KeycloakPrincipalMode.STABLE) {
            throw new IllegalStateException("Portable backups require taxonomy.keycloak.principal-mode=STABLE; "
                    + "existing OIDC ownership must be explicitly migrated before enabling stable identities");
        }
        // Requiring initializedIdentities makes the V1-V3 schema migration precede this check.
        // This is a fail-closed startup check, not approval for a production mode switch.
        if (mode == KeycloakPrincipalMode.STABLE) LegacyOwnerMigrationReadiness.requireReady(database);
        return mode;
    }
}
