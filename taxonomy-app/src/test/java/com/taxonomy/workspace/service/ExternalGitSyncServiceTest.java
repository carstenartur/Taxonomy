package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ExternalGitSyncService}.
 *
 * <p>Verifies topology mode guard, status retrieval, and credential
 * configuration. Actual Transport operations are not tested here
 * because they require a running Git server.
 */
class ExternalGitSyncServiceTest {

    private DslGitRepositoryFactory factory;
    private SystemRepositoryService systemRepositoryService;
    private ExternalGitSyncService externalSyncService;

    @BeforeEach
    void setUp() {
        factory = new DslGitRepositoryFactory(null); // in-memory mode
        systemRepositoryService = mock(SystemRepositoryService.class);
        externalSyncService = new ExternalGitSyncService(factory, systemRepositoryService);
    }

    @Test
    void fetchFromExternal_throwsInInternalSharedMode() {
        SystemRepository sysRepo = createSystemRepository(RepositoryTopologyMode.INTERNAL_SHARED);
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(sysRepo);

        assertThrows(IllegalStateException.class,
                () -> externalSyncService.fetchFromExternal(),
                "Fetch should throw in INTERNAL_SHARED mode");
    }

    @Test
    void pushToExternal_throwsInInternalSharedMode() {
        SystemRepository sysRepo = createSystemRepository(RepositoryTopologyMode.INTERNAL_SHARED);
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(sysRepo);

        assertThrows(IllegalStateException.class,
                () -> externalSyncService.pushToExternal("draft"),
                "Push should throw in INTERNAL_SHARED mode");
    }

    @Test
    void fullSync_throwsInInternalSharedMode() {
        SystemRepository sysRepo = createSystemRepository(RepositoryTopologyMode.INTERNAL_SHARED);
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(sysRepo);

        assertThrows(IllegalStateException.class,
                () -> externalSyncService.fullSync("alice"),
                "Full sync should throw in INTERNAL_SHARED mode");
    }

    @Test
    void fetchFromExternal_throwsWhenExternalUrlNotConfigured() {
        SystemRepository sysRepo = createSystemRepository(RepositoryTopologyMode.EXTERNAL_CANONICAL);
        sysRepo.setExternalUrl(null);
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(sysRepo);

        assertThrows(IllegalStateException.class,
                () -> externalSyncService.fetchFromExternal(),
                "Fetch should throw when external URL is not configured");
    }

    @Test
    void getStatus_returnsExternalDisabledForInternalMode() {
        SystemRepository sysRepo = createSystemRepository(RepositoryTopologyMode.INTERNAL_SHARED);
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(sysRepo);

        var status = externalSyncService.getStatus();

        assertFalse(status.externalEnabled());
        assertNull(status.externalUrl());
    }

    @Test
    void getStatus_returnsExternalEnabledForExternalMode() {
        SystemRepository sysRepo = createSystemRepository(RepositoryTopologyMode.EXTERNAL_CANONICAL);
        sysRepo.setExternalUrl("http://gitea:3000/taxonomy/shared.git");
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(sysRepo);

        var status = externalSyncService.getStatus();

        assertTrue(status.externalEnabled());
        assertEquals("http://gitea:3000/taxonomy/shared.git", status.externalUrl());
    }

    @Test
    void getStatus_handlesNoSystemRepository() {
        when(systemRepositoryService.getPrimaryRepository())
                .thenThrow(new IllegalStateException("No primary repo"));

        var status = externalSyncService.getStatus();

        assertFalse(status.externalEnabled());
        assertNull(status.externalUrl());
    }

    private SystemRepository createSystemRepository(RepositoryTopologyMode mode) {
        SystemRepository sysRepo = new SystemRepository();
        sysRepo.setRepositoryId(UUID.randomUUID().toString());
        sysRepo.setDisplayName("Test Repository");
        sysRepo.setTopologyMode(mode);
        sysRepo.setDefaultBranch("draft");
        sysRepo.setPrimaryRepo(true);
        sysRepo.setCreatedAt(Instant.now());
        return sysRepo;
    }
}
