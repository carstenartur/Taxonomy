package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SystemRepositoryService}.
 *
 * <p>Verifies auto-creation of the primary system repository on startup
 * and retrieval of the shared branch name.
 */
class SystemRepositoryServiceTest {

    private SystemRepositoryRepository repository;
    private SystemRepositoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(SystemRepositoryRepository.class);
        when(repository.save(any(SystemRepository.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        service = new SystemRepositoryService(repository);
    }

    @Test
    void ensureSystemRepository_createsWhenNoneExists() {
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.empty());

        service.ensureSystemRepository();

        verify(repository).save(argThat(repo -> {
            assertNotNull(repo.getRepositoryId());
            assertEquals("Shared Architecture Repository", repo.getDisplayName());
            assertEquals(RepositoryTopologyMode.INTERNAL_SHARED, repo.getTopologyMode());
            assertEquals("draft", repo.getDefaultBranch());
            assertTrue(repo.isPrimaryRepo());
            assertNotNull(repo.getCreatedAt());
            return true;
        }));
    }

    @Test
    void ensureSystemRepository_doesNotCreateWhenAlreadyExists() {
        SystemRepository existing = new SystemRepository();
        existing.setPrimaryRepo(true);
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.of(existing));

        service.ensureSystemRepository();

        verify(repository, never()).save(any());
    }

    @Test
    void getPrimaryRepository_returnsExistingRepo() {
        SystemRepository sysRepo = new SystemRepository();
        sysRepo.setRepositoryId("test-id");
        sysRepo.setDefaultBranch("draft");
        sysRepo.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.of(sysRepo));

        SystemRepository result = service.getPrimaryRepository();
        assertEquals("test-id", result.getRepositoryId());
    }

    @Test
    void getPrimaryRepository_throwsWhenNoneExists() {
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.getPrimaryRepository());
    }

    @Test
    void getSharedBranch_returnsConfiguredBranch() {
        SystemRepository sysRepo = new SystemRepository();
        sysRepo.setDefaultBranch("main");
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.of(sysRepo));

        assertEquals("main", service.getSharedBranch());
    }

    @Test
    void getSharedBranch_fallsToDraftWhenNotAvailable() {
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.empty());

        assertEquals("draft", service.getSharedBranch());
    }
}
