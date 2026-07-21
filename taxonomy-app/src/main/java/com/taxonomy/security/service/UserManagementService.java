package com.taxonomy.security.service;

import com.taxonomy.security.model.AppRole;
import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.RoleRepository;
import com.taxonomy.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Application service for local user and role administration. */
@Service
@Transactional
public class UserManagementService {

    public static final int MIN_PASSWORD_LENGTH = 8;
    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(UserRepository userRepository,
                                 RoleRepository roleRepository,
                                 PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUsers() {
        return userRepository.findAll().stream().map(this::toUserMap).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getUser(Long id) {
        return userRepository.findById(id).map(this::toUserMap);
    }

    public Map<String, Object> createUser(Map<String, Object> body, String actor) {
        String username = stringValue(body.get("username"));
        String password = stringValue(body.get("password"));
        String displayName = stringValue(body.get("displayName"));
        String email = stringValue(body.get("email"));
        List<String> roles = stringList(body.get("roles"));

        if (username == null || username.isBlank()) {
            throw new ValidationException("Username is required.");
        }
        validatePassword(password);
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ConflictException("Username '" + username + "' already exists.");
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEnabled(true);
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setRoles(resolveRoles(roles));
        AppUser saved = userRepository.save(user);
        log.info("USER_CREATED user={} roles={} by={}", username,
                saved.getRoles().stream().map(AppRole::getName).sorted().toList(), actor);
        return toUserMap(saved);
    }

    public Map<String, Object> updateUser(Long id, Map<String, Object> body, String actor) {
        AppUser user = requireUser(id);

        if (body.containsKey("displayName")) {
            user.setDisplayName(stringValue(body.get("displayName")));
        }
        if (body.containsKey("email")) {
            user.setEmail(stringValue(body.get("email")));
        }
        if (body.containsKey("enabled")) {
            boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
            if (!enabled && isLastAdmin(user)) {
                throw new ValidationException("Cannot disable the last admin user.");
            }
            user.setEnabled(enabled);
        }
        if (body.containsKey("roles")) {
            Set<AppRole> newRoles = resolveRoles(stringList(body.get("roles")));
            boolean removingAdmin = hasRole(user, "ROLE_ADMIN")
                    && newRoles.stream().noneMatch(role -> "ROLE_ADMIN".equals(role.getName()));
            if (removingAdmin && isLastAdmin(user)) {
                throw new ValidationException("Cannot remove ADMIN role from the last admin user.");
            }
            user.setRoles(newRoles);
        }

        AppUser saved = userRepository.save(user);
        log.info("USER_UPDATED user={} by={}", saved.getUsername(), actor);
        return toUserMap(saved);
    }

    public void changePassword(Long id, String newPassword, String actor) {
        validatePassword(newPassword);
        AppUser user = requireUser(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("USER_PASSWORD_CHANGED user={} by={}", user.getUsername(), actor);
    }

    public String disableUser(Long id, String actor) {
        AppUser user = requireUser(id);
        if (isLastAdmin(user)) {
            throw new ValidationException("Cannot disable the last admin user.");
        }
        user.setEnabled(false);
        userRepository.save(user);
        log.info("USER_DISABLED user={} by={}", user.getUsername(), actor);
        return user.getUsername();
    }

    private AppUser requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ValidationException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
    }

    private boolean isLastAdmin(AppUser user) {
        if (!hasRole(user, "ROLE_ADMIN")) {
            return false;
        }
        long enabledAdmins = userRepository.findAll().stream()
                .filter(AppUser::isEnabled)
                .filter(candidate -> hasRole(candidate, "ROLE_ADMIN"))
                .count();
        return enabledAdmins <= 1;
    }

    private boolean hasRole(AppUser user, String roleName) {
        return user.getRoles().stream().anyMatch(role -> roleName.equals(role.getName()));
    }

    private Set<AppRole> resolveRoles(List<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return roleRepository.findByName("ROLE_USER").map(Set::of).orElse(Set.of());
        }
        Set<AppRole> roles = new HashSet<>();
        for (String roleName : roleNames) {
            if (roleName == null || roleName.isBlank()) {
                continue;
            }
            String normalized = roleName.startsWith("ROLE_") ? roleName : "ROLE_" + roleName;
            roleRepository.findByName(normalized).ifPresent(roles::add);
        }
        if (roles.isEmpty()) {
            throw new ValidationException("At least one valid role is required.");
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
        map.put("roles", user.getRoles().stream().map(AppRole::getName).sorted().toList());
        return map;
    }

    private String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof String string) {
                values.add(string);
            }
        }
        return values;
    }

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }
}
