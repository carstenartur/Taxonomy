package com.taxonomy.security.controller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Only editable profile fields are bound. Passwords and account state never enter this model. */
public record UserManagementForm(
        @NotBlank @Size(max = 255) String username,
        @Size(max = 255) String displayName,
        @Email @Size(max = 255) String email,
        @NotEmpty @Size(max = 3)
        List<@Pattern(regexp = "ROLE_(USER|ARCHITECT|ADMIN)") String> roles) {

    public UserManagementForm {
        username = clean(username);
        displayName = clean(displayName);
        email = clean(email);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public static UserManagementForm empty() {
        return new UserManagementForm("", "", "", List.of("ROLE_USER"));
    }

    public static UserManagementForm from(Map<String, Object> account) {
        List<String> roles = account.get("roles") instanceof List<?> values
                ? values.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
        return new UserManagementForm(text(account.get("username")),
                text(account.get("displayName")), text(account.get("email")), roles);
    }

    public Map<String, Object> profile() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("displayName", displayName);
        body.put("email", email);
        body.put("roles", roles);
        return body;
    }

    private static String text(Object value) {
        return value instanceof String string ? string : "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
