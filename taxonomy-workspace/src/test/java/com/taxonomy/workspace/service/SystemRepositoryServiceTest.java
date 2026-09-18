package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryOwnerType;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void ensureSystemRepositoryCreatesCataloguedPrimaryWhenNoneExists() {
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.empty());

        service.ensureSystemRepository();

        verify(repository).save(argThat(systemRepository -> {
            assertNotNull(systemRepository.getRepositoryId());
            assertEquals(SystemRepositoryService.PRIMARY_STORAGE_NAME,
                    systemRepository.getStorageRepositoryName());
            assertEquals("shared-architecture", systemRepository.getSlug());
            assertEquals("Shared Architecture Repository", systemRepository.getDisplayName());
            assertEquals(RepositoryTopologyMode.INTERNAL_SHARED,
                    systemRepository.getTopologyMode());
            assertEquals(RepositoryVisibility.ORGANIZATION,
                    systemRepository.getVisibility());
            assertEquals(RepositoryLifecycleState.ACTIVE,
                    systemRepository.getLifecycleState());
            assertNull(systemRepository.getProvisioningError());
            assertEquals(RepositoryOwnerType.SYSTEM, systemRepository.getOwnerType());
            assertEquals("system", systemRepository.getOwnerId());
            assertEquals("draft", systemRepository.getDefaultBranch());
            assertTrue(systemRepository.isPrimaryRepo());
            assertNotNull(systemRepository.getCreatedAt());
            assertNotNull(systemRepository.getUpdatedAt());
            return true;
        }));
    }

    @Test
    void ensureSystemRepositoryDoesNotWriteCleanExistingRecord() {
        SystemRepository existing = cleanExistingPrimary();
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.of(existing));

        service.ensureSystemRepository();

        verify(repository, never()).save(any());
    }

    @Test
    void ensureSystemRepositoryBackfillsLegacyPrimaryCatalogMetadata() {
        SystemRepository existing = new SystemRepository();
        existing.setPrimaryRepo(true);
        existing.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(existing, "storageRepositoryName", null);
        ReflectionTestUtils.setField(existing, "slug", null);
        ReflectionTestUtils.setField(existing, "visibility", null);
        ReflectionTestUtils.setField(existing, "lifecycleState", null);
        ReflectionTestUtils.setField(existing, "ownerType", null);
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.of(existing));

        service.ensureSystemRepository();

        assertEquals(SystemRepositoryService.PRIMARY_STORAGE_NAME,
                existing.getStorageRepositoryName());
        assertEquals("shared-architecture", existing.getSlug());
        assertEquals(RepositoryVisibility.ORGANIZATION, existing.getVisibility());
        assertEquals(RepositoryLifecycleState.ACTIVE, existing.getLifecycleState());
        assertEquals(RepositoryOwnerType.SYSTEM, existing.getOwnerType());
        assertEquals("system", existing.getOwnerId());
        verify(repository).save(existing);
    }

    @Test
    void ensureSystemRepositoryErasesLegacyPlaintextCredential() {
        SystemRepository existing = cleanExistingPrimary();
        ReflectionTestUtils.setField(existing, "legacyExternalAuthToken", "secret-token");
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.of(existing));

        service.ensureSystemRepository();

        assertFalse(existing.hasLegacyPlaintextCredential());
        verify(repository).save(existing);
    }

    @Test
    void ensureSystemRepositoryFailsClosedWhenCredentialCleanupCannotPersist() {
        SystemRepository existing = cleanExistingPrimary();
        ReflectionTestUtils.setField(existing, "legacyExternalAuthToken", "secret-token");
        when(repository.findByPrimaryRepoTrue()).thenReturn(Optional.of(existing));
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository).save(existing);

        assertThrows(IllegalStateException.class, service::ensureSystemRepository);
    }

    @Test
    void createCentralRepositoryReservesProvisioningCatalogIdentity() {
        when(repository.findBySlug("customer-a")).thenReturn(Optional.empty());
        when(repository.findByStorageRepositoryName(any())).thenReturn(Optional.empty());

        SystemRepository created = service.createCentralRepository(
                "Customer A",
                "Customer A",
                "Architecture",
                RepositoryVisibility.PRIVATE,
                "alice",
                "main");

        assertNotNull(created.getRepositoryId());
        assertEquals("customer-a", created.getSlug());
        assertEquals("central-" + created.getRepositoryId(), created.getStorageRepositoryName());
        assertEquals("alice", created.getOwnerId());
        assertEquals("main", created.getDefaultBranch());
        assertEquals(RepositoryLifecycleState.PROVISIONING, created.getLifecycleState());
        assertNull(created.getProvisioningError());
        assertFalse(created.isPrimaryRepo());
    }

    @Test
    void markProvisioningReadyActivatesRepositoryAndClearsPreviousError() {
        SystemRepository provisioning = catalogRepository("repo-a");
        provisioning.setLifecycleState(RepositoryLifecycleState.PROVISIONING);
        provisioning.setProvisioningError("old failure");
        when(repository.findByRepositoryId("repo-a")).thenReturn(Optional.of(provisioning));

        SystemRepository ready = service.markProvisioningReady("repo-a");

        assertEquals(RepositoryLifecycleState.ACTIVE, ready.getLifecycleState());
        assertNull(ready.getProvisioningError());
        assertNotNull(ready.getUpdatedAt());
        verify(repository).save(provisioning);
    }

    @Test
    void markProvisioningFailedPreservesDescriptionAndStoresDiagnosticSeparately() {
        SystemRepository provisioning = catalogRepository("repo-a");
        provisioning.setDescription("Business description");
        provisioning.setLifecycleState(RepositoryLifecycleState.PROVISIONING);
        when(repository.findByRepositoryId("repo-a")).thenReturn(Optional.of(provisioning));

        SystemRepository failed = service.markProvisioningFailed(
                "repo-a", "logical storage allocation failed");

        assertEquals(RepositoryLifecycleState.FAILED, failed.getLifecycleState());
        assertEquals("logical storage allocation failed", failed.getProvisioningError());
        assertEquals("Business description", failed.getDescription());
        verify(repository).save(provisioning);
    }

    @Test
    void listActiveRepositoriesDelegatesToScopedCatalogQuery() {
        SystemRepository one = cleanExistingPrimary();
        when(repository.findByLifecycleStateOrderByDisplayNameAsc(RepositoryLifecycleState.ACTIVE))
                .thenReturn(List.of(one));

        assertEquals(List.of(one), service.listActiveRepositories());
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

    private static SystemRepository cleanExistingPrimary() {
        SystemRepository existing = catalogRepository("primary-id");
        existing.setPrimaryRepo(true);
        existing.setStorageRepositoryName(SystemRepositoryService.PRIMARY_STORAGE_NAME);
        existing.setSlug("shared-architecture");
        existing.setDisplayName("Shared Architecture Repository");
        existing.setVisibility(RepositoryVisibility.ORGANIZATION);
        existing.setLifecycleState(RepositoryLifecycleState.ACTIVE);
        existing.setOwnerType(RepositoryOwnerType.SYSTEM);
        existing.setOwnerId("system");
        existing.setDefaultBranch("draft");
        existing.setCreatedBy("system");
        return existing;
    }

    private static SystemRepository catalogRepository(String repositoryId) {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId(repositoryId);
        repository.setStorageRepositoryName("central-" + repositoryId);
        repository.setSlug(repositoryId);
        repository.setDisplayName(repositoryId);
        repository.setVisibility(RepositoryVisibility.PRIVATE);
        repository.setOwnerType(RepositoryOwnerType.USER);
        repository.setOwnerId("alice");
        repository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        repository.setDefaultBranch("main");
        repository.setCreatedBy("alice");
        repository.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        repository.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return repository;
    }
}
