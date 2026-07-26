package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemRepositoryServiceTest {

    private SystemRepositoryRepository repository;
    private SystemRepositoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(SystemRepositoryRepository.class);
        when(repository.save(any(SystemRepository.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new SystemRepositoryService(repository);
    }

    @Test
    void ensureSystemRepositoryCreatesWhenNoneExists() {
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.empty());

        service.ensureSystemRepository();

        verify(repository).save(argThat(systemRepository -> {
            assertNotNull(systemRepository.getRepositoryId());
            assertEquals("Shared Architecture Repository", systemRepository.getDisplayName());
            assertEquals(RepositoryTopologyMode.INTERNAL_SHARED,
                    systemRepository.getTopologyMode());
            assertEquals("draft", systemRepository.getDefaultBranch());
            assertTrue(systemRepository.isPrimaryRepo());
            assertNotNull(systemRepository.getCreatedAt());
            return true;
        }));
    }

    @Test
    void ensureSystemRepositoryDoesNotWriteCleanExistingRecord() {
        SystemRepository existing = new SystemRepository();
        existing.setPrimaryRepo(true);
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.of(existing));

        service.ensureSystemRepository();

        verify(repository, never()).save(any());
    }

    @Test
    void ensureSystemRepositoryErasesLegacyPlaintextCredential() {
        SystemRepository existing = new SystemRepository();
        existing.setPrimaryRepo(true);
        ReflectionTestUtils.setField(existing, "legacyExternalAuthToken", "secret-token");
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.of(existing));

        service.ensureSystemRepository();

        assertFalse(existing.hasLegacyPlaintextCredential());
        verify(repository).save(existing);
    }

    @Test
    void ensureSystemRepositoryFailsClosedWhenCredentialCleanupCannotPersist() {
        SystemRepository existing = new SystemRepository();
        existing.setPrimaryRepo(true);
        ReflectionTestUtils.setField(existing, "legacyExternalAuthToken", "secret-token");
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.of(existing));
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository).save(existing);

        assertThrows(IllegalStateException.class, service::ensureSystemRepository);
    }

    @Test
    void getPrimaryRepositoryReturnsExistingRepository() {
        SystemRepository systemRepository = new SystemRepository();
        systemRepository.setRepositoryId("test-id");
        systemRepository.setDefaultBranch("draft");
        systemRepository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.of(systemRepository));

        SystemRepository result = service.getPrimaryRepository();

        assertEquals("test-id", result.getRepositoryId());
    }

    @Test
    void getPrimaryRepositoryThrowsWhenNoneExists() {
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, service::getPrimaryRepository);
    }

    @Test
    void getSharedBranchReturnsConfiguredBranch() {
        SystemRepository systemRepository = new SystemRepository();
        systemRepository.setDefaultBranch("main");
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.of(systemRepository));

        assertEquals("main", service.getSharedBranch());
    }

    @Test
    void getSharedBranchFallsBackToDraftWhenNotAvailable() {
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.empty());

        assertEquals("draft", service.getSharedBranch());
    }
}
