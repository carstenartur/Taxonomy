package com.taxonomy.security.keycloak;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

/**
 * Error handler for Keycloak/JWT-related authentication errors.
 * <p>
 * Provides user-friendly error responses when JWT token validation fails
 * (e.g., expired token, invalid signature, Keycloak not reachable).
 */
@ControllerAdvice
@Profile("keycloak")
public class KeycloakErrorHandler {

    @ExceptionHandler(JwtValidationException.class)
    public ResponseEntity<Map<String, String>> handleJwtError(JwtValidationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "error", "Token validation failed",
                        "detail", ex.getMessage()
                ));
    }
}
