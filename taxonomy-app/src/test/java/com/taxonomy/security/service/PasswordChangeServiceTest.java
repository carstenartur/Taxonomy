package com.taxonomy.security.service;

import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordChangeServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private BootstrapAdminCredentialStore bootstrapCredentialStore;
    private PasswordChangeService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        bootstrapCredentialStore = mock(BootstrapAdminCredentialStore.class);
        service = new PasswordChangeService(
                userRepository, passwordEncoder, bootstrapCredentialStore);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void successfulAdministratorChangeRemovesPublishedBootstrapCredential() {
        AppUser admin = user("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("current-secret", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("replacement-secret"))
                .thenReturn("replacement-hash");

        PasswordChangeService.Result result = service.changePassword(
                "admin",
                "current-secret",
                "replacement-secret",
                "replacement-secret");

        assertThat(result).isEqualTo(PasswordChangeService.Result.CHANGED);
        assertThat(admin.getPasswordHash()).isEqualTo("replacement-hash");
        assertThat(admin.isMustChangePassword()).isFalse();
        verify(userRepository).save(admin);
        verify(bootstrapCredentialStore).deletePublishedCredential();
    }

    @Test
    void successfulTransactionalAdministratorChangeDeletesOnlyAfterCommit() {
        AppUser admin = user("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("current-secret", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("replacement-secret"))
                .thenReturn("replacement-hash");
        TransactionSynchronizationManager.initSynchronization();

        PasswordChangeService.Result result = service.changePassword(
                "admin",
                "current-secret",
                "replacement-secret",
                "replacement-secret");

        assertThat(result).isEqualTo(PasswordChangeService.Result.CHANGED);
        verify(bootstrapCredentialStore, never()).deletePublishedCredential();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());

        verify(bootstrapCredentialStore).deletePublishedCredential();
    }

    @Test
    void changingAnotherLocalUserDoesNotRemoveAdministratorCredential() {
        AppUser user = user("architect");
        when(userRepository.findByUsername("architect")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current-secret", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("replacement-secret"))
                .thenReturn("replacement-hash");

        PasswordChangeService.Result result = service.changePassword(
                "architect",
                "current-secret",
                "replacement-secret",
                "replacement-secret");

        assertThat(result).isEqualTo(PasswordChangeService.Result.CHANGED);
        verify(bootstrapCredentialStore, never()).deletePublishedCredential();
    }

    @Test
    void rejectedChangeKeepsBootstrapCredentialAvailable() {
        AppUser admin = user("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("wrong-secret", "old-hash")).thenReturn(false);

        PasswordChangeService.Result result = service.changePassword(
                "admin",
                "wrong-secret",
                "replacement-secret",
                "replacement-secret");

        assertThat(result)
                .isEqualTo(PasswordChangeService.Result.CURRENT_PASSWORD_INCORRECT);
        verify(userRepository, never()).save(admin);
        verify(bootstrapCredentialStore, never()).deletePublishedCredential();
    }

    @Test
    void reportsPasswordChangeRequirementOnlyForAnExistingFlaggedUser() {
        AppUser admin = user("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());

        assertThat(service.isPasswordChangeRequired(null)).isFalse();
        assertThat(service.isPasswordChangeRequired("missing")).isFalse();
        assertThat(service.isPasswordChangeRequired("admin")).isTrue();

        admin.setMustChangePassword(false);
        assertThat(service.isPasswordChangeRequired("admin")).isFalse();
    }

    @Test
    void returnsSpecificValidationResultsWithoutPersistingOrDeleting() {
        AppUser admin = user("admin");
        when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("current-secret", "old-hash")).thenReturn(true);

        assertThat(service.changePassword(
                "missing", "current-secret", "replacement-secret", "replacement-secret"))
                .isEqualTo(PasswordChangeService.Result.USER_NOT_FOUND);
        assertThat(service.changePassword(
                "admin", null, "replacement-secret", "replacement-secret"))
                .isEqualTo(PasswordChangeService.Result.CURRENT_PASSWORD_INCORRECT);
        assertThat(service.changePassword(
                "admin", "current-secret", null, null))
                .isEqualTo(PasswordChangeService.Result.TOO_SHORT);
        assertThat(service.changePassword(
                "admin", "current-secret", "short", "short"))
                .isEqualTo(PasswordChangeService.Result.TOO_SHORT);
        assertThat(service.changePassword(
                "admin", "current-secret", "replacement-secret", "different-secret"))
                .isEqualTo(PasswordChangeService.Result.CONFIRMATION_MISMATCH);
        assertThat(service.changePassword(
                "admin", "current-secret", "current-secret", "current-secret"))
                .isEqualTo(PasswordChangeService.Result.SAME_AS_CURRENT);

        verify(userRepository, never()).save(admin);
        verify(bootstrapCredentialStore, never()).deletePublishedCredential();
    }

    private static AppUser user(String username) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash("old-hash");
        user.setMustChangePassword(true);
        return user;
    }
}
