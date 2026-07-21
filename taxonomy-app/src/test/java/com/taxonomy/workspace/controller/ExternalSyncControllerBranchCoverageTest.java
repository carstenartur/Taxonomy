package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.ExternalGitSyncService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExternalSyncControllerBranchCoverageTest {

    @Mock private ExternalGitSyncService externalGitSyncService;
    @Mock private SystemRepositoryService systemRepositoryService;
    @Mock private WorkspaceResolver workspaceResolver;
    @Mock private FetchResult fetchResult;
    @Mock private PushResult pushResult;

    private ExternalSyncController controller;

    @BeforeEach
    void setUp() {
        controller = new ExternalSyncController(externalGitSyncService, systemRepositoryService, workspaceResolver);
        lenient().when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
    }

    @Test
    void fetchCoversSuccessConfigurationAndUnexpectedFailure() throws Exception {
        when(fetchResult.getTrackingRefUpdates()).thenReturn(List.of());
        when(externalGitSyncService.fetchFromExternal()).thenReturn(fetchResult);
        assertThat(controller.fetchFromExternal().getBody())
                .containsEntry("success", true).containsEntry("updates", 0);

        when(externalGitSyncService.fetchFromExternal()).thenThrow(new IllegalStateException("not configured"));
        var configurationFailure = controller.fetchFromExternal();
        assertThat(configurationFailure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(configurationFailure.getBody()).containsEntry("error", "Configuration error");

        when(externalGitSyncService.fetchFromExternal()).thenThrow(new IOException("network"));
        var unexpectedFailure = controller.fetchFromExternal();
        assertThat(unexpectedFailure.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(unexpectedFailure.getBody()).containsEntry("error", "Fetch failed");
    }

    @Test
    void pushCoversExplicitDefaultAndBothFailureClasses() throws Exception {
        when(externalGitSyncService.pushToExternal("feature")).thenReturn(pushResult);
        assertThat(controller.pushToExternal("feature").getBody())
                .containsEntry("success", true).containsEntry("branch", "feature");

        when(systemRepositoryService.getSharedBranch()).thenReturn("shared");
        when(externalGitSyncService.pushToExternal("shared")).thenReturn(pushResult);
        assertThat(controller.pushToExternal(null).getBody()).containsEntry("branch", "shared");

        when(externalGitSyncService.pushToExternal("invalid"))
                .thenThrow(new IllegalStateException("external mode required"));
        assertThat(controller.pushToExternal("invalid").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        when(externalGitSyncService.pushToExternal("broken")).thenThrow(new IOException("network"));
        assertThat(controller.pushToExternal("broken").getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void fullSyncCoversSuccessConfigurationAndUnexpectedFailure() throws Exception {
        when(externalGitSyncService.fullSync("alice")).thenReturn("commit-1");
        assertThat(controller.fullSync().getBody())
                .containsEntry("success", true).containsEntry("commitId", "commit-1");

        when(externalGitSyncService.fullSync("alice")).thenThrow(new IllegalStateException("disabled"));
        assertThat(controller.fullSync().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        when(externalGitSyncService.fullSync("alice")).thenThrow(new IOException("network"));
        assertThat(controller.fullSync().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void statusMapsEveryServiceField() {
        Instant fetchAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant pushAt = Instant.parse("2026-01-02T00:00:00Z");
        when(externalGitSyncService.getStatus()).thenReturn(new ExternalGitSyncService.ExternalSyncStatus(
                true, "https://example.invalid/repo.git", fetchAt, pushAt, "abc"));

        assertThat(controller.getStatus().getBody())
                .containsEntry("externalEnabled", true)
                .containsEntry("externalUrl", "https://example.invalid/repo.git")
                .containsEntry("lastFetchAt", fetchAt)
                .containsEntry("lastPushAt", pushAt)
                .containsEntry("lastFetchCommit", "abc");
    }

    @Test
    void configureCoversFullPartialInvalidAndPersistenceFailure() {
        SystemRepository repository = repository();
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(repository);

        var full = controller.configure("https://example.invalid/repo.git", "EXTERNAL_CANONICAL");
        assertThat(full.getBody())
                .containsEntry("success", true)
                .containsEntry("topologyMode", "EXTERNAL_CANONICAL")
                .containsEntry("externalUrl", "https://example.invalid/repo.git");
        verify(systemRepositoryService).save(repository);

        repository.setExternalUrl("existing");
        repository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        assertThat(controller.configure(null, null).getBody())
                .containsEntry("externalUrl", "existing")
                .containsEntry("topologyMode", "INTERNAL_SHARED");

        assertThat(controller.configure(null, "NOT_A_MODE").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        doThrow(new IllegalStateException("database unavailable"))
                .when(systemRepositoryService).save(repository);
        assertThat(controller.configure("new-url", "INTERNAL_SHARED").getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static SystemRepository repository() {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId("system");
        repository.setDisplayName("System");
        repository.setDefaultBranch("draft");
        repository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        return repository;
    }
}
