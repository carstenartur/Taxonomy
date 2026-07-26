package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Focused service tests for external synchronization guards and status. */
class ExternalGitSyncServiceTest {

    private DslGitRepositoryFactory factory;
    private SystemRepositoryService systemRepositoryService;
    private ExternalGitSyncService externalSyncService;

    @BeforeEach
    void setUp() {
        factory = new DslGitRepositoryFactory(null);
        systemRepositoryService = mock(SystemRepositoryService.class);
        externalSyncService = new ExternalGitSyncService(factory, systemRepositoryService);
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    void fetchFromExternalThrowsInInternalSharedMode() {
        SystemRepository systemRepository = createSystemRepository(
                RepositoryTopologyMode.INTERNAL_SHARED);
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(systemRepository);

        assertThrows(IllegalStateException.class,
                () -> externalSyncService.fetchFromExternal());
    }

    @Test
    void pushToExternalThrowsInInternalSharedMode() {
        SystemRepository systemRepository = createSystemRepository(
                RepositoryTopologyMode.INTERNAL_SHARED);
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(systemRepository);

        assertThrows(IllegalStateException.class,
                () -> externalSyncService.pushToExternal("draft"));
    }

    @Test
    void fullSyncThrowsInInternalSharedMode() {
        SystemRepository systemRepository = createSystemRepository(
                RepositoryTopologyMode.INTERNAL_SHARED);
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(systemRepository);

        assertThrows(IllegalStateException.class,
                () -> externalSyncService.fullSync("alice"));
    }

    @Test
    void fetchFromExternalThrowsWhenExternalUrlNotConfigured() {
        SystemRepository systemRepository = createSystemRepository(
                RepositoryTopologyMode.EXTERNAL_CANONICAL);
        systemRepository.setExternalUrl(null);
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(systemRepository);

        assertThrows(IllegalStateException.class,
                () -> externalSyncService.fetchFromExternal());
    }

    @Test
    void rejectsHttpUserInformationAndPasswords() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalGitSyncService.validateExternalUrl(
                        "https://token@example.test/team/repo.git"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalGitSyncService.validateExternalUrl(
                        "https://user:secret@example.test/team/repo.git"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalGitSyncService.validateExternalUrl(
                        "ssh://git:secret@example.test/team/repo.git"));
    }

    @Test
    void acceptsCredentialFreeHttpsAndSshUserSyntax() {
        assertEquals(
                "https://example.test/team/repo.git",
                ExternalGitSyncService.validateExternalUrl(
                        "https://example.test/team/repo.git").toString());
        assertEquals(
                "git@example.test:team/repo.git",
                ExternalGitSyncService.validateExternalUrl(
                        "git@example.test:team/repo.git").toString());
    }

    @Test
    void executionRejectsPreviouslyPersistedCredentialUrlBeforeTransport() {
        SystemRepository systemRepository = createSystemRepository(
                RepositoryTopologyMode.EXTERNAL_CANONICAL);
        systemRepository.setExternalUrl(
                "https://user:secret@example.test/team/repo.git");
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(systemRepository);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> externalSyncService.fetchFromExternal());

        assertTrue(error.getMessage().contains("must not contain credentials"));
    }

    @Test
    void getStatusReturnsExternalDisabledForInternalMode() {
        SystemRepository systemRepository = createSystemRepository(
                RepositoryTopologyMode.INTERNAL_SHARED);
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(systemRepository);

        var status = externalSyncService.getStatus();

        assertFalse(status.externalEnabled());
        assertNull(status.externalUrl());
    }

    @Test
    void getStatusReturnsExternalEnabledForExternalMode() {
        SystemRepository systemRepository = createSystemRepository(
                RepositoryTopologyMode.EXTERNAL_CANONICAL);
        systemRepository.setExternalUrl("http://gitea:3000/taxonomy/shared.git");
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(systemRepository);

        var status = externalSyncService.getStatus();

        assertTrue(status.externalEnabled());
        assertEquals("http://gitea:3000/taxonomy/shared.git", status.externalUrl());
    }

    @Test
    void getStatusHandlesNoSystemRepository() {
        when(systemRepositoryService.getPrimaryRepository())
                .thenThrow(new IllegalStateException("No primary repo"));

        var status = externalSyncService.getStatus();

        assertFalse(status.externalEnabled());
        assertNull(status.externalUrl());
    }

    private SystemRepository createSystemRepository(RepositoryTopologyMode mode) {
        SystemRepository systemRepository = new SystemRepository();
        systemRepository.setRepositoryId(UUID.randomUUID().toString());
        systemRepository.setDisplayName("Test Repository");
        systemRepository.setTopologyMode(mode);
        systemRepository.setDefaultBranch("draft");
        systemRepository.setPrimaryRepo(true);
        systemRepository.setCreatedAt(Instant.now());
        return systemRepository;
    }
}
