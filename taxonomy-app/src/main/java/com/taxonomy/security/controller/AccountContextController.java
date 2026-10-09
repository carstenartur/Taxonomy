package com.taxonomy.security.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.security.config.LocalUserManagementAccess;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Read-only capability context for the authenticated user interface. */
@RestController
@Tag(name = "Account")
@RequestMapping("/api/account")
public class AccountContextController {
    private final Environment environment;

    public AccountContextController(Environment environment) {
        this.environment = environment;
    }


    @GetMapping("/me")
    @Operation(summary = "Read authenticated account capabilities",
            description = "Returns the current username, granted roles and UI capability flags for administration, architecture mutation and local-user management. These flags describe current authority but do not replace server-side authorization.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<?> currentAccount(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "AUTHENTICATION_REQUIRED"));
        }

        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String value = authority.getAuthority();
            if (value.startsWith("ROLE_")) {
                roles.add(value.substring(5));
            }
        }

        boolean administrator = roles.contains("ADMIN");
        boolean architectureMutationAllowed = administrator || roles.contains("ARCHITECT");
        return ResponseEntity.ok(Map.of(
                "username", authentication.getName(),
                "roles", roles,
                "architectureMutationAllowed", architectureMutationAllowed,
                "administrator", administrator,
                "localUserManagementAllowed", administrator && LocalUserManagementAccess.isEnabled(environment)));
    }
}
