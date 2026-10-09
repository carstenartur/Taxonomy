package com.taxonomy.security.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.security.service.PasswordChangeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Self-service account operations for local API clients. */
@RestController
@Tag(name = "Account")
@RequestMapping("/api/account")
@Profile("!keycloak")
@ConditionalOnProperty(name = "taxonomy.security.change-password-enabled",
        havingValue = "true", matchIfMissing = true)
public class AccountApiController {

    private final PasswordChangeService passwordChangeService;

    public AccountApiController(PasswordChangeService passwordChangeService) {
        this.passwordChangeService = passwordChangeService;
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change the current local account password",
            description = "Local authentication only; unavailable in the Keycloak profile or when password change is disabled. Requires currentPassword, a different valid newPassword and matching confirmPassword. Changes only the authenticated account and returns PASSWORD_CHANGED on success.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<Map<String, String>> changePassword(
            Authentication authentication,
            @RequestBody Map<String, String> body) {
        if (authentication == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "AUTHENTICATION_REQUIRED"));
        }

        PasswordChangeService.Result result = passwordChangeService.changePassword(
                authentication.getName(),
                body.get("currentPassword"),
                body.get("newPassword"),
                body.get("confirmPassword"));

        if (result == PasswordChangeService.Result.CHANGED) {
            return ResponseEntity.ok(Map.of(
                    "status", "PASSWORD_CHANGED",
                    "message", "Password changed successfully"));
        }

        return ResponseEntity.badRequest().body(Map.of(
                "error", result.name(),
                "message", messageFor(result)));
    }

    private static String messageFor(PasswordChangeService.Result result) {
        return switch (result) {
            case USER_NOT_FOUND -> "User not found";
            case CURRENT_PASSWORD_INCORRECT -> "Current password is incorrect";
            case TOO_SHORT -> "New password must be at least "
                    + PasswordChangeService.MINIMUM_PASSWORD_LENGTH + " characters";
            case CONFIRMATION_MISMATCH -> "New passwords do not match";
            case SAME_AS_CURRENT -> "New password must differ from the current password";
            case CHANGED -> "Password changed successfully";
        };
    }
}
