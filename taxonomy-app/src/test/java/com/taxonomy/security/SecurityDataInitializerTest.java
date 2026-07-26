package com.taxonomy.security;

import com.taxonomy.security.config.SecurityDataInitializer;
import com.taxonomy.security.model.AppRole;
import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.RoleRepository;
import com.taxonomy.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityDataInitializerTest {

    private static final DefaultApplicationArguments NO_ARGS =
            new DefaultApplicationArguments(new String[0]);

    private RoleRepository roleRepository;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        roleRepository = mock(RoleRepository.class);
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder(4);

        when(roleRepository.findByName(anyString()))
                .thenAnswer(invocation -> Optional.of(
                        new AppRole(invocation.getArgument(0))));
        when(userRepository.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void missingPasswordCreatesRandomOneTimeBootstrapCredential() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        SecurityDataInitializer initializer = new SecurityDataInitializer(
                roleRepository,
                userRepository,
                passwordEncoder,
                "",
                true);

        initializer.run(NO_ARGS);

        verify(userRepository).save(any(AppUser.class));
        var saved = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(saved.capture());
        AppUser admin = saved.getValue();
        assertThat(admin.getUsername()).isEqualTo("admin");
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(passwordEncoder.matches("admin", admin.getPasswordHash())).isFalse();
        assertThat(admin.getRoles())
                .extracting(AppRole::getName)
                .containsExactlyInAnyOrder(
                        "ROLE_USER", "ROLE_ARCHITECT", "ROLE_ADMIN");
    }

    @Test
    void explicitDevelopmentPasswordUsesConfiguredChangePolicy() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        SecurityDataInitializer initializer = new SecurityDataInitializer(
                roleRepository,
                userRepository,
                passwordEncoder,
                "explicit-local-test-secret",
                false);

        initializer.run(NO_ARGS);

        var saved = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(saved.capture());
        assertThat(passwordEncoder.matches(
                "explicit-local-test-secret", saved.getValue().getPasswordHash()))
                .isTrue();
        assertThat(saved.getValue().isMustChangePassword()).isFalse();
    }

    @Test
    void changedExistingAdministratorIsNotLockedAgainOnRestart() {
        AppUser existing = new AppUser();
        existing.setUsername("admin");
        existing.setPasswordHash(passwordEncoder.encode("already-changed-secret"));
        existing.setMustChangePassword(false);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(existing));

        new SecurityDataInitializer(
                roleRepository,
                userRepository,
                passwordEncoder,
                "configured-but-not-reapplied",
                true).run(NO_ARGS);

        assertThat(existing.isMustChangePassword()).isFalse();
        verify(userRepository, never()).save(existing);
    }

    @Test
    void legacyBuiltInCredentialIsMarkedForReplacementOnce() {
        AppUser existing = new AppUser();
        existing.setUsername("admin");
        existing.setPasswordHash(passwordEncoder.encode("admin"));
        existing.setMustChangePassword(false);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(existing));

        new SecurityDataInitializer(
                roleRepository,
                userRepository,
                passwordEncoder,
                "",
                true).run(NO_ARGS);

        assertThat(existing.isMustChangePassword()).isTrue();
        verify(userRepository).save(existing);
    }
}
