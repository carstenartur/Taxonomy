package com.taxonomy.security;

import com.taxonomy.security.controller.UserManagementForm;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserManagementFormTest {
    @Test
    void newProfileContainsNoCredentialsAndUsesTheLowestRole() {
        var form = UserManagementForm.empty();
        assertThat(form.roles()).containsExactly("ROLE_USER");
        assertThat(form.profile()).containsOnlyKeys("displayName", "email", "roles");
        assertThat(form.username()).isEmpty();
    }

    @Test
    void normalizesOptionalFieldsWithoutBindingPrivilegedState() {
        var form = new UserManagementForm(" alice ", null, " a@example.test ", null);
        assertThat(form.username()).isEqualTo("alice");
        assertThat(form.displayName()).isEmpty();
        assertThat(form.email()).isEqualTo("a@example.test");
        assertThat(form.roles()).isEmpty();
        assertThat(form.profile()).doesNotContainKeys("username", "enabled", "password", "passwordHash");
    }

    @Test
    void mapsOnlySafeProfileFieldsFromTheExistingProjection() {
        var form = UserManagementForm.from(Map.of("username", "alice", "displayName", "Alice",
                "roles", List.of("ROLE_USER", 123), "passwordHash", "must-not-escape"));
        assertThat(form.roles()).containsExactly("ROLE_USER");
        assertThat(form.email()).isEmpty();
        assertThat(form.toString()).doesNotContain("must-not-escape");
        assertThat(UserManagementForm.from(Map.of()).roles()).isEmpty();
    }

    @Test
    void serverValidationRejectsBlankNamesInvalidEmailAndUnknownRoles() {
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var form = new UserManagementForm(" ", "Alice", "bad-email", List.of("ROLE_ROOT"));
            var errors = new BeanPropertyBindingResult(form, "form");
            validator.validate(form, errors);
            assertThat(errors.hasFieldErrors("username")).isTrue();
            assertThat(errors.hasFieldErrors("email")).isTrue();
            assertThat(errors.hasFieldErrors("roles[0]")).isTrue();
        }
    }
}
