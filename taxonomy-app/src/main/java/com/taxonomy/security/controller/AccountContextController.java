package com.taxonomy.security.controller;

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
@RequestMapping("/api/account")
public class AccountContextController {

    @GetMapping("/me")
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
                "administrator", administrator));
    }
}
