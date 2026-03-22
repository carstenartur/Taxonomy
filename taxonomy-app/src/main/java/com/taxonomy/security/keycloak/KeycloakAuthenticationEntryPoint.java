package com.taxonomy.security.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Custom {@link AuthenticationEntryPoint} for the Keycloak/JWT resource server.
 * <p>
 * Returns a structured JSON 401 response when JWT authentication fails
 * (e.g., missing, expired, or invalid token). Invoked by the Spring Security
 * filter chain before any controller is reached, ensuring consistent error
 * handling for all JWT validation failures.
 * <p>
 * The error message is generic to avoid leaking internal validation details.
 */
@Component
@Profile("keycloak")
public class KeycloakAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MAPPER.writeValue(response.getOutputStream(), Map.of(
                "error", "Authentication required",
                "status", 401
        ));
    }
}
