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
import static org.mockito.Mockito.doReturn;
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
        controller = new ExternalSyncController(
                externalGitSyncService, systemRepositoryService, workspaceResolver);
        lenient().when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        lenient().when(pushResult.getRemoteUpdates()).thenReturn(List.of());
    }

    @Test
    void fetchCoversSuccessConfigurationAndUnexpectedFailure() throws Exception {
        when(fetchResult.getTrackingRefUpdates()).thenReturn(List.of());
        doReturn(fetchResult).when(externalGitSyncService).fetchFromExternal();
        assertThat(controller.fetchFromExternal().getBody())
                .containsEntry("success", true)
                .containsEntry("updates", 0);

        doThrow(new IllegalStateException("not configured"))
                .when(externalGitSyncService).fetchFromExternal();
        var configurationFailure = controller.fetchFromExternal();
        assertThat(configurationFailure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(configurationFailure.getBody())
                .containsEntry("error", "Configuration error");

        doThrow(new IOException("network"))
                .when(externalGitSyncService).fetchFromExternal();
        var unexpectedFailure = controller.fetchFromExternal();
        assertThat(unexpectedFailure.getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(unexpectedFailure.getBody()).containsEntry("error", "FETCH_FAILED");
    }

    @Test
    void pushCoversExplicitDefaultRejectedAndUnexpectedFailures() throws Exception {
        doReturn(pushResult)
                .when(externalGitSyncService).pushToExternal("feature");
        assertThat(controller.pushToExternal("feature").getBody())
                .containsEntry("success", true)
                .containsEntry("branch", "feature")
                .containsEntry("updates", 0);

        when(systemRepositoryService.getSharedBranch()).thenReturn("shared");
        doReturn(pushResult)
                .when(externalGitSyncService).pushToExternal("shared");
        assertThat(controller.pushToExternal(null).getBody())
                .containsEntry("branch", "shared");

        doThrow(new IllegalStateException("external mode required"))
                .when(externalGitSyncService).pushToExternal("invalid");
        assertThat(controller.pushToExternal("invalid").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        doThrow(new ExternalGitSyncService.ExternalPushRejectedException("rejected"))
                .when(externalGitSyncService).pushToExternal("rejected");
        var rejected = controller.pushToExternal("rejected");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected.getBody())
                .containsEntry("success", false)
                .containsEntry("error", "PUSH_REJECTED");

        doThrow(new IOException("network"))
                .when(externalGitSyncService).pushToExternal("broken");
        var broken = controller.pushToExternal("broken");
        assertThat(broken.getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(broken.getBody()).containsEntry("error", "PUSH_FAILED");
    }

    @Test
    void fullSyncCoversSuccessMissingRemoteConflictAndFailures() throws Exception {
        doReturn("commit-1")
                .when(externalGitSyncService).fullSync("alice");
        assertThat(controller.fullSync().getBody())
                .containsEntry("success", true)
                .containsEntry("status", "INTEGRATED")
                .containsEntry("commitId", "commit-1");

        doReturn(null)
                .when(externalGitSyncService).fullSync("alice");
        assertThat(controller.fullSync().getBody())
                .containsEntry("status", "NO_REMOTE_BRANCH");

        doThrow(new ExternalGitSyncService.ExternalSyncConflictException("conflict"))
                .when(externalGitSyncService).fullSync("alice");
        var conflict = controller.fullSync();
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody())
                .containsEntry("success", false)
                .containsEntry("error", "MERGE_CONFLICT");

        doThrow(new IllegalStateException("disabled"))
                .when(externalGitSyncService).fullSync("alice");
        assertThat(controller.fullSync().getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        doThrow(new IOException("network"))
                .when(externalGitSyncService).fullSync("alice");
        var failure = controller.fullSync();
        assertThat(failure.getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(failure.getBody()).containsEntry("error", "FULL_SYNC_FAILED");
    }

    @Test
    void statusMapsEveryNonSecretServiceField() {
        Instant fetchAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant pushAt = Instant.parse("2026-01-02T00:00:00Z");
        when(externalGitSyncService.getStatus()).thenReturn(
                new ExternalGitSyncService.ExternalSyncStatus(
                        true,
                        "https://example.invalid/repo.git",
                        true,
                        fetchAt,
                        pushAt,
                        "abc"));

        assertThat(controller.getStatus().getBody())
                .containsEntry("externalEnabled", true)
                .containsEntry("externalUrl", "https://example.invalid/repo.git")
                .containsEntry("credentialConfigured", true)
                .containsEntry("lastFetchAt", fetchAt)
                .containsEntry("lastPushAt", pushAt)
                .containsEntry("lastFetchCommit", "abc")
                .doesNotContainKeys("token", "externalAuthToken");
    }

    @Test
    void configureCoversFullPartialInvalidAndPersistenceFailure() {
        SystemRepository repository = repository();
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(repository);

        var full = controller.configure(
                "https://example.invalid/repo.git", "EXTERNAL_CANONICAL");
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

        assertThat(controller.configure(null, "NOT_A_MODE").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        doThrow(new IllegalStateException("database unavailable"))
                .when(systemRepositoryService).save(repository);
        var failure = controller.configure(
                "https://example.invalid/new-repo.git", "INTERNAL_SHARED");
        assertThat(failure.getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(failure.getBody())
                .containsEntry("error", "CONFIGURATION_FAILED");
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
