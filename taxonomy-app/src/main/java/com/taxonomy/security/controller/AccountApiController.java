package com.taxonomy.security.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
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
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            description = "JSON password-change fields. The account is selected exclusively from authentication; additional string fields are ignored. Session-authenticated callers require a CSRF token.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(type = "object",
                            requiredProperties = {"currentPassword", "newPassword", "confirmPassword"},
                            additionalPropertiesSchema = String.class),
                    schemaProperties = {
                            @SchemaProperty(name = "currentPassword", schema = @Schema(type = "string",
                                    format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                                    description = "Current password of the authenticated local account.")),
                            @SchemaProperty(name = "newPassword", schema = @Schema(type = "string",
                                    format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                                    description = "Replacement password, at least twelve Java UTF-16 code units and different from currentPassword. Non-BMP characters count as two code units in the existing service.")),
                            @SchemaProperty(name = "confirmPassword", schema = @Schema(type = "string",
                                    format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                                    description = "Must exactly match newPassword."))
                    }))
    @ApiResponse(responseCode = "200", description = "Password changed; the mandatory-change flag is cleared",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(type = "object", requiredProperties = {"status", "message"}),
                    schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(type = "string",
                                    allowableValues = "PASSWORD_CHANGED", description = "Successful password change.")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string",
                                    description = "Human-readable confirmation."))
                    }))
    @ApiResponse(responseCode = "400", description = "Invalid JSON/body or rejected password change. Domain validation returns PasswordChangeRejection; binding errors are produced by the configured framework error handler.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(anyOf = {PasswordChangeRejection.class, PasswordChangeBindingError.class})))
    @ApiResponse(responseCode = "401", description = "Authentication required or rejected. Security may reject before the controller; do not depend on a particular error body.",
            content = @Content)
    @ApiResponse(responseCode = "403", description = "Access denied, including an absent or invalid CSRF token for session-authenticated requests",
            content = @Content)
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

    /** Documentation view only: the existing open-map response binding remains unchanged. */
    @Schema(name = "PasswordChangeRejection", description = "Rejected domain validation; the password was not changed.")
    public interface PasswordChangeRejection {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"USER_NOT_FOUND", "CURRENT_PASSWORD_INCORRECT", "TOO_SHORT",
                        "CONFIRMATION_MISMATCH", "SAME_AS_CURRENT"},
                description = "The first failed validation of the current account or password fields.")
        String getError();

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Human-readable reason for the rejection.")
        String getMessage();
    }

    /** Framework binding errors are not restricted to the domain validation code set. */
    @Schema(name = "PasswordChangeBindingError", type = "object",
            additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
            description = "Framework error object for an unreadable or missing JSON body; fields depend on the configured error handler.")
    public interface PasswordChangeBindingError { }
}
