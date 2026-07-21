package com.taxonomy.security.controller;

import com.taxonomy.security.service.UserManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    @Operation(summary = "Get user by ID")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable Long id) {
        return userManagementService.getUser(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Create a new user")
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
    @Operation(summary = "Update user details (roles, displayName, email, enabled)")
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
    @Operation(summary = "Change a user's password (admin action)")
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
    @Operation(summary = "Disable a user (soft delete)")
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
