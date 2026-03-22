package com.taxonomy.security.keycloak;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Adds an OAuth2 security scheme to the OpenAPI spec when the Keycloak profile
 * is active. Swagger UI will show an "Authorize" button that starts the
 * authorization-code flow against Keycloak.
 */
@Configuration
@Profile("keycloak")
public class SwaggerKeycloakConfig {

    @Bean
    public OpenAPI keycloakOpenAPI(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://localhost:8180/realms/taxonomy}") String issuerUri) {
        return new OpenAPI()
                .addSecurityItem(new SecurityRequirement().addList("keycloak"))
                .components(new Components()
                        .addSecuritySchemes("keycloak", new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .flows(new OAuthFlows()
                                        .authorizationCode(new OAuthFlow()
                                                .authorizationUrl(issuerUri + "/protocol/openid-connect/auth")
                                                .tokenUrl(issuerUri + "/protocol/openid-connect/token")
                                        )
                                )
                        )
                );
    }
}
