package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Loads the shipped profile and Boot decoder without repeating the full HTTP tests. */
class KeycloakAudienceConfigurationTest {
    @Test void customClientIdBecomesTheRequiredApiAudience() throws Exception {
        try (var issuer = KeycloakJwtTestIssuer.start()) {
            String accepted = issuer.token(List.of("custom-taxonomy-client"));
            String defaultAudience = issuer.token(List.of("taxonomy-app"));
            runner(issuer).withPropertyValues("KEYCLOAK_CLIENT_ID=custom-taxonomy-client")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(JwtDecoder.class);
                        var decoder = context.getBean(JwtDecoder.class);
                        assertThat(decoder.decode(accepted).getAudience()).contains("custom-taxonomy-client");
                        assertThatThrownBy(() -> decoder.decode(defaultAudience)).isInstanceOf(JwtException.class);
                    });
        }
    }

    @Test void standardSpringAudienceOverrideCanNameADistinctApiResource() throws Exception {
        try (var issuer = KeycloakJwtTestIssuer.start()) {
            String accepted = issuer.token(List.of("taxonomy-api"));
            String browserClientAudience = issuer.token(List.of("custom-browser-client"));
            String defaultAudience = issuer.token(List.of("taxonomy-app"));
            runner(issuer).withPropertyValues("KEYCLOAK_CLIENT_ID=custom-browser-client",
                            "spring.security.oauth2.resourceserver.jwt.audiences=taxonomy-api")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(JwtDecoder.class);
                        var decoder = context.getBean(JwtDecoder.class);
                        assertThat(decoder.decode(accepted).getAudience()).contains("taxonomy-api");
                        assertThatThrownBy(() -> decoder.decode(browserClientAudience)).isInstanceOf(JwtException.class);
                        assertThatThrownBy(() -> decoder.decode(defaultAudience)).isInstanceOf(JwtException.class);
                    });
        }
    }

    private ApplicationContextRunner runner(KeycloakJwtTestIssuer issuer) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration.class))
                .withPropertyValues("spring.profiles.active=keycloak",
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuer.origin(),
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + issuer.origin() + "/keys");
    }
}
