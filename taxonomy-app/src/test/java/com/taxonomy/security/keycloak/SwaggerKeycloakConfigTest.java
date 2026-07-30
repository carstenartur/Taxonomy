package com.taxonomy.security.keycloak;

import com.taxonomy.shared.config.OpenApiConfig;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class SwaggerKeycloakConfigTest {

    @Test
    void keycloakProfileCustomizesTheSingleSharedOpenApiBean() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("keycloak");
            context.register(OpenApiConfig.class, SwaggerKeycloakConfig.class);
            context.refresh();

            assertThat(context.getBeansOfType(OpenAPI.class))
                    .containsOnlyKeys("taxonomyOpenAPI");

            OpenAPI openApi = context.getBean(OpenAPI.class);
            context.getBeansOfType(OpenApiCustomizer.class).values()
                    .forEach(customizer -> customizer.customise(openApi));

            assertThat(openApi.getSecurity()).hasSize(1);
            assertThat(openApi.getSecurity().getFirst()).containsKey("keycloak");
            assertThat(openApi.getComponents().getSecuritySchemes())
                    .containsKey("keycloak");
            assertThat(openApi.getComponents().getSecuritySchemes().get("keycloak")
                    .getFlows().getAuthorizationCode().getAuthorizationUrl())
                    .endsWith("/protocol/openid-connect/auth");
        }
    }
}
