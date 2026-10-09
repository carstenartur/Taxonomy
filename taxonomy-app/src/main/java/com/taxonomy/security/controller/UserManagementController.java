package com.taxonomy.security.controller;

import com.taxonomy.security.service.UserManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Admin-only REST adapter for local user management. */
@RestController
@RequestMapping("/api/admin/users")
@Profile("!keycloak")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "User Management", description = "Admin-only user CRUD operations")
@ConditionalOnProperty(name = "taxonomy.security.local-users-enabled",
        havingValue = "true", matchIfMissing = true)
public class UserManagementController {

    private final UserManagementService userManagementService;

    public UserManagementController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping
    @Operation(summary = "List all users", description = "Returns all users without password hashes.")
    public List<Map<String, Object>> listUsers() {
        return userManagementService.listUsers();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get user by ID",
            description = "Reads one local user administration record. Unknown users return 404; credential secrets are not returned as part of the user view.")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable Long id) {
        return userManagementService.getUser(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Create a new user",
            description = "Creates a local user account with validated metadata and roles, recording the authenticated administrator as actor. Returns 201, or 409 for a conflicting account and 400 for invalid data.")
    public ResponseEntity<Object> createUser(@RequestBody Map<String, Object> body,
                                              Authentication authentication) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(userManagementService.createUser(body, actor(authentication)));
        } catch (UserManagementService.ConflictException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", exception.getMessage()));
        } catch (UserManagementService.ValidationException exception) {
            return badRequest(exception.getMessage());
        }
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update user details (roles, displayName, email, enabled)",
            description = "Updates a local account's roles, display name, email or enabled state through the user-management service. Invalid changes are rejected and unknown users return 404; passwords use a separate command.")
    public ResponseEntity<Object> updateUser(@PathVariable Long id,
                                              @RequestBody Map<String, Object> body,
                                              Authentication authentication) {
        try {
            return ResponseEntity.ok(userManagementService.updateUser(id, body, actor(authentication)));
        } catch (UserManagementService.NotFoundException exception) {
            return ResponseEntity.notFound().build();
        } catch (UserManagementService.ValidationException exception) {
            return badRequest(exception.getMessage());
        }
    }

    @PutMapping("/{id}/password")
    @Operation(summary = "Change a user's password (admin action)",
            description = "Performs an administrator password change for a local account, recording the authenticated actor. Validates the replacement password, returns 404 for an unknown user and 400 for rejected changes; does not modify Keycloak credentials.")
    public ResponseEntity<Object> changePassword(@PathVariable Long id,
                                                  @RequestBody Map<String, String> body,
                                                  Authentication authentication) {
        try {
            userManagementService.changePassword(id, body.get("password"), actor(authentication));
            return ResponseEntity.ok(Map.of("message", "Password changed successfully."));
        } catch (UserManagementService.NotFoundException exception) {
            return ResponseEntity.notFound().build();
        } catch (UserManagementService.ValidationException exception) {
            return badRequest(exception.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Disable a user (soft delete)",
            description = "Disables a local user account through a soft-delete administrative action and records its actor. Returns a confirmation message without deleting the account's historical records.")
    public ResponseEntity<Object> disableUser(@PathVariable Long id,
                                               Authentication authentication) {
        try {
            String username = userManagementService.disableUser(id, actor(authentication));
            return ResponseEntity.ok(Map.of(
                    "message", "User '" + username + "' has been disabled."));
        } catch (UserManagementService.NotFoundException exception) {
            return ResponseEntity.notFound().build();
        } catch (UserManagementService.ValidationException exception) {
            return badRequest(exception.getMessage());
        }
    }

    private String actor(Authentication authentication) {
        return authentication != null ? authentication.getName() : "system";
    }

    private ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
