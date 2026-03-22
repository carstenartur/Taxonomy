package com.taxonomy.security.keycloak;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Health indicator that checks whether the Keycloak JWKS endpoint is reachable.
 * Appears in {@code /actuator/health} when the {@code keycloak} profile is active.
 */
@Component
@Profile("keycloak")
public class KeycloakHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(KeycloakHealthIndicator.class);

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:http://localhost:8180/realms/taxonomy/protocol/openid-connect/certs}")
    private String jwkSetUri;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public Health health() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(jwkSetUri))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return Health.up()
                        .withDetail("jwksEndpoint", jwkSetUri)
                        .build();
            } else {
                return Health.down()
                        .withDetail("jwksEndpoint", jwkSetUri)
                        .withDetail("statusCode", response.statusCode())
                        .build();
            }
        } catch (Exception e) {
            log.debug("Keycloak health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("jwksEndpoint", jwkSetUri)
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
