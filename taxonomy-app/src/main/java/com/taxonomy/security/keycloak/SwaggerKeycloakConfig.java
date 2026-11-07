package com.taxonomy.security.keycloak;

import com.taxonomy.security.access.ApiPermission;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.Scopes;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Adds an OAuth2 security scheme to the shared OpenAPI specification when the
 * Keycloak profile is active. Swagger UI will show an "Authorize" button that
 * starts the authorization-code flow against Keycloak.
 */
@Configuration
@Profile("keycloak")
public class SwaggerKeycloakConfig {

    @Bean
    public OpenApiCustomizer keycloakOpenApiCustomizer(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:"
                    + "http://localhost:8180/realms/taxonomy}")
            String issuerUri,
            @Value("${taxonomy.keycloak.swagger.authorization-url:}") String authorizationUrl,
            @Value("${taxonomy.keycloak.swagger.token-url:}") String tokenUrl) {
        String base = issuerUri.replaceAll("/+$", "");
        String authorize = authorizationUrl.isBlank() ? base + "/protocol/openid-connect/auth" : authorizationUrl;
        String token = tokenUrl.isBlank() ? base + "/protocol/openid-connect/token" : tokenUrl;
        return openApi -> {
            openApi.addSecurityItem(new SecurityRequirement().addList("keycloak"));
            Components components = openApi.getComponents();
            if (components == null) {
                components = new Components();
                openApi.setComponents(components);
            }
            components.addSecuritySchemes("keycloak", new SecurityScheme()
                    .type(SecurityScheme.Type.OAUTH2)
                    .flows(new OAuthFlows()
                            .authorizationCode(new OAuthFlow()
                                    .authorizationUrl(authorize).tokenUrl(token).scopes(scopes()))
                            .clientCredentials(new OAuthFlow().tokenUrl(token).scopes(scopes()))));
        };
    }

    private static Scopes scopes() {
        var scopes = new Scopes();
        ApiPermission.scopes().forEach(scopes::addString);
        return scopes;
    }
}
