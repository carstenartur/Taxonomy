package com.taxonomy.security;

import com.taxonomy.security.model.AppRole;
import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.RoleRepository;
import com.taxonomy.security.repository.UserRepository;
import com.taxonomy.security.service.UserManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserManagementServiceTest {

    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private UserManagementService service;
    private AppRole userRole;
    private AppRole adminRole;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        userRole = new AppRole("ROLE_USER");
        adminRole = new AppRole("ROLE_ADMIN");
        when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.of(userRole));
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        service = new UserManagementService(
                userRepository, roleRepository, new BCryptPasswordEncoder());
    }

    @Test
    void createUserValidatesAndPersistsWithoutExposingPasswordHash() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.save(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(7L);
            return user;
        });

        var result = service.createUser(java.util.Map.of(
                "username", "alice",
                "password", "StrongPassword!2026",
                "roles", List.of("USER")), "admin");

        assertThat(result)
                .containsEntry("id", 7L)
                .containsEntry("username", "alice")
                .containsEntry("mustChangePassword", false)
                .doesNotContainKey("passwordHash");
        verify(userRepository).save(any(AppUser.class));
    }

    @Test
    void enforcedPolicyMarksCreatedAndResetPasswordsAsTemporary() {
        ReflectionTestUtils.setField(service, "requirePasswordChange", true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.save(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            if (user.getId() == null) user.setId(7L);
            return user;
        });

        service.createUser(java.util.Map.of(
                "username", "alice",
                "password", "StrongPassword!2026",
                "roles", List.of("USER")), "admin");

        var created = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(created.capture());
        assertThat(created.getValue().isMustChangePassword()).isTrue();

        AppUser existing = created.getValue();
        when(userRepository.findById(7L)).thenReturn(Optional.of(existing));
        service.changePassword(7L, "ReplacementPassword!2026", "admin");
        assertThat(existing.isMustChangePassword()).isTrue();
    }

    @Test
    void duplicateUsernameAndShortPasswordAreRejected() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new AppUser()));

        assertThatThrownBy(() -> service.createUser(java.util.Map.of(
                "username", "alice", "password", "StrongPassword!2026"), "admin"))
                .isInstanceOf(UserManagementService.ConflictException.class);

        assertThatThrownBy(() -> service.createUser(java.util.Map.of(
                "username", "bob", "password", "short"), "admin"))
                .isInstanceOf(UserManagementService.ValidationException.class)
                .hasMessageContaining("at least");
    }

    @Test
    void lastAdminCannotBeDisabledOrLoseAdminRole() {
        AppUser admin = user(1L, "admin", true, Set.of(adminRole, userRole));
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findAll()).thenReturn(List.of(admin));

        assertThatThrownBy(() -> service.disableUser(1L, "admin"))
                .isInstanceOf(UserManagementService.ValidationException.class)
                .hasMessageContaining("last admin");

        assertThatThrownBy(() -> service.updateUser(1L,
                java.util.Map.of("roles", List.of("USER")), "admin"))
                .isInstanceOf(UserManagementService.ValidationException.class)
                .hasMessageContaining("last admin");
    }

    @Test
    void secondAdminAllowsDisablingOneAdmin() {
        AppUser first = user(1L, "admin-one", true, Set.of(adminRole));
        AppUser second = user(2L, "admin-two", true, Set.of(adminRole));
        when(userRepository.findById(1L)).thenReturn(Optional.of(first));
        when(userRepository.findAll()).thenReturn(List.of(first, second));
        when(userRepository.save(first)).thenReturn(first);

        assertThat(service.disableUser(1L, "admin-two")).isEqualTo("admin-one");
        assertThat(first.isEnabled()).isFalse();
    }

    @Test
    void unknownUserIsReportedWithoutRepositoryLeakage() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePassword(999L, "StrongPassword!2026", "admin"))
                .isInstanceOf(UserManagementService.NotFoundException.class);
    }

    private AppUser user(Long id, String username, boolean enabled, Set<AppRole> roles) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername(username);
        user.setEnabled(enabled);
        user.setRoles(roles);
        return user;
    }
}
