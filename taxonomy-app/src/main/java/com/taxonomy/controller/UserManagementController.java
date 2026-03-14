package com.taxonomy.controller;

import com.taxonomy.model.AppRole;
import com.taxonomy.model.AppUser;
import com.taxonomy.repository.RoleRepository;
import com.taxonomy.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin-only REST API for managing application users.
 * <p>
 * All endpoints require {@code ROLE_ADMIN}. The last remaining admin user
 * cannot be disabled or have the ADMIN role removed — this prevents lockout.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "User Management", description = "Admin-only user CRUD operations")
public class UserManagementController {

    private static final Logger log = LoggerFactory.getLogger(UserManagementController.class);
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserManagementController(UserRepository userRepository,
                                    RoleRepository roleRepository,
                                    PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    @Operation(summary = "List all users", description = "Returns all users without password hashes.")
    public List<Map<String, Object>> listUsers() {
        return userRepository.findAll().stream()
                .map(this::toUserMap)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get user by ID")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(user -> ResponseEntity.ok(toUserMap(user)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Create a new user")
    public ResponseEntity<Object> createUser(@RequestBody Map<String, Object> body,
                                             Authentication authentication) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        String displayName = (String) body.get("displayName");
        String email = (String) body.get("email");
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) body.get("roles");

        // Validation
        if (username == null || username.isBlank()) {
            return badRequest("Username is required.");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Username '" + username + "' already exists."));
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEnabled(true);
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setRoles(resolveRoles(roles));
        userRepository.save(user);

        log.info("USER_CREATED user={} roles={} by={}", username,
                user.getRoles().stream().map(AppRole::getName).collect(Collectors.joining(",")),
                authentication.getName());

        return ResponseEntity.status(HttpStatus.CREATED).body(toUserMap(user));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update user details (roles, displayName, email, enabled)")
    public ResponseEntity<Object> updateUser(@PathVariable Long id,
                                             @RequestBody Map<String, Object> body,
                                             Authentication authentication) {
        Optional<AppUser> optionalUser = userRepository.findById(id);
        if (optionalUser.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        AppUser user = optionalUser.get();

        if (body.containsKey("displayName")) {
            user.setDisplayName((String) body.get("displayName"));
        }
        if (body.containsKey("email")) {
            user.setEmail((String) body.get("email"));
        }
        if (body.containsKey("enabled")) {
            boolean enabled = (Boolean) body.get("enabled");
            if (!enabled && isLastAdmin(user)) {
                return badRequest("Cannot disable the last admin user.");
            }
            user.setEnabled(enabled);
        }
        if (body.containsKey("roles")) {
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) body.get("roles");
            Set<AppRole> newRoles = resolveRoles(roles);
            boolean removingAdmin = user.getRoles().stream()
                    .anyMatch(r -> r.getName().equals("ROLE_ADMIN"))
                    && newRoles.stream().noneMatch(r -> r.getName().equals("ROLE_ADMIN"));
            if (removingAdmin && isLastAdmin(user)) {
                return badRequest("Cannot remove ADMIN role from the last admin user.");
            }
            user.setRoles(newRoles);
        }

        userRepository.save(user);
        log.info("USER_UPDATED user={} by={}", user.getUsername(), authentication.getName());

        return ResponseEntity.ok(toUserMap(user));
    }

    @PutMapping("/{id}/password")
    @Operation(summary = "Change a user's password (admin action)")
    public ResponseEntity<Object> changePassword(@PathVariable Long id,
                                                 @RequestBody Map<String, String> body,
                                                 Authentication authentication) {
        Optional<AppUser> optionalUser = userRepository.findById(id);
        if (optionalUser.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String newPassword = body.get("password");
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            return badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }

        AppUser user = optionalUser.get();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("USER_PASSWORD_CHANGED user={} by={}", user.getUsername(), authentication.getName());

        return ResponseEntity.ok(Map.of("message", "Password changed successfully."));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Disable a user (soft delete)")
    public ResponseEntity<Object> disableUser(@PathVariable Long id,
                                              Authentication authentication) {
        Optional<AppUser> optionalUser = userRepository.findById(id);
        if (optionalUser.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        AppUser user = optionalUser.get();
        if (isLastAdmin(user)) {
            return badRequest("Cannot disable the last admin user.");
        }

        user.setEnabled(false);
        userRepository.save(user);
        log.info("USER_DISABLED user={} by={}", user.getUsername(), authentication.getName());

        return ResponseEntity.ok(Map.of("message", "User '" + user.getUsername() + "' has been disabled."));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isLastAdmin(AppUser user) {
        boolean userIsAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("ROLE_ADMIN"));
        if (!userIsAdmin) {
            return false;
        }
        long adminCount = userRepository.findAll().stream()
                .filter(AppUser::isEnabled)
                .filter(u -> u.getRoles().stream().anyMatch(r -> r.getName().equals("ROLE_ADMIN")))
                .count();
        return adminCount <= 1;
    }

    private Set<AppRole> resolveRoles(List<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            // Default to USER role
            return roleRepository.findByName("ROLE_USER")
                    .map(Set::of)
                    .orElse(Set.of());
        }
        Set<AppRole> roles = new HashSet<>();
        for (String roleName : roleNames) {
            String normalized = roleName.startsWith("ROLE_") ? roleName : "ROLE_" + roleName;
            roleRepository.findByName(normalized).ifPresent(roles::add);
        }
        return roles;
    }

    private Map<String, Object> toUserMap(AppUser user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("displayName", user.getDisplayName());
        map.put("email", user.getEmail());
        map.put("enabled", user.isEnabled());
        map.put("roles", user.getRoles().stream()
                .map(AppRole::getName)
                .sorted()
                .collect(Collectors.toList()));
        return map;
    }

    private ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
