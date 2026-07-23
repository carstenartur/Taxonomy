package com.taxonomy.security.controller;

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
