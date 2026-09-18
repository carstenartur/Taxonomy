package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verify persisted Git contents and history, not just mocked routing calls. */
class WorkspaceProvisioningSnapshotTest {
    private static final String CAPTURED = "meta { language: \"taxdsl\"; version: \"captured\"; }";
    private static final String LATER = "meta { language: \"taxdsl\"; version: \"later\"; }";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void provisionsOnlyTheCapturedSnapshotWithoutAnImplicitSeed(boolean automaticDefault) throws Exception {
        try (var factory = spy(new DslGitRepositoryFactory(null))) {
            var source = spy(factory.getSystemRepository());
            doReturn(source).when(factory).getSystemRepository();
            String capturedCommit = source.commitDsl("draft", CAPTURED, "system", "Captured source");
            doAnswer(invocation -> {
                String snapshot = (String) invocation.callRealMethod();
                source.commitDsl("draft", LATER, "system", "Concurrent source change");
                return snapshot;
            }).when(source).getDslAtCommit(capturedCommit);
            var workspace = workspace(automaticDefault);
            var manager = new WorkspaceManager(rows(workspace), 50, catalogue(), factory);

            UserWorkspace ready = automaticDefault
                    ? manager.provisionDefaultWorkspaceRepository("snapshot-owner", workspace.getWorkspaceId())
                    : manager.provisionWorkspaceRepository("snapshot-owner", workspace.getWorkspaceId());

            var destination = factory.openWorkspaceRepository(workspace.getWorkspaceId());
            String branch = automaticDefault ? "draft" : "main";
            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertEquals(capturedCommit, ready.getBaseCommit());
            assertNotEquals(capturedCommit, source.getHeadCommit("draft"));
            assertEquals(CAPTURED, destination.getDslAtHead(branch));
            assertEquals(destination.getHeadCommit(branch), ready.getCurrentCommit());
            assertEquals(List.of(branch), destination.getBranchNames(), "No implicit draft branch may be added");
            assertEquals(1, destination.getDslHistory(branch).size(), "No unreviewed seed commit may precede the snapshot");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\n"})
    void rejectsBlankSourceBeforeOpeningTheDestination(String blankDsl) throws Exception {
        try (var factory = spy(new DslGitRepositoryFactory(null))) {
            factory.getSystemRepository().commitDsl("draft", blankDsl, "system", "Unusable source");
            var workspace = workspace(false);
            var manager = new WorkspaceManager(rows(workspace), 50, catalogue(), factory);

            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> manager.provisionWorkspaceRepository("snapshot-owner", workspace.getWorkspaceId()));

            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(WorkspaceProvisioningStatus.FAILED, workspace.getProvisioningStatus());
            assertNotNull(workspace.getProvisioningError());
            assertNull(workspace.getCurrentCommit());
            assertNull(workspace.getProvisionedAt());
            verify(factory, never()).openWorkspaceRepository(anyString());
            verify(factory, never()).getWorkspaceRepository(anyString());
        }
    }

    private static UserWorkspace workspace(boolean automaticDefault) {
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId("captured-snapshot-" + automaticDefault);
        workspace.setUsername("snapshot-owner");
        workspace.setCurrentBranch("draft");
        workspace.setBaseBranch("draft");
        workspace.setDefault(automaticDefault);
        workspace.setCreatedAt(Instant.now());
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        return workspace;
    }

    private static UserWorkspaceRepository rows(UserWorkspace workspace) {
        var rows = mock(UserWorkspaceRepository.class);
        when(rows.findByWorkspaceId(workspace.getWorkspaceId())).thenReturn(Optional.of(workspace));
        when(rows.claimProvisioning(anyString(), anyString(),
                eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection())).thenReturn(1);
        when(rows.save(any(UserWorkspace.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return rows;
    }

    private static SystemRepositoryService catalogue() {
        var repository = new SystemRepository();
        repository.setRepositoryId("snapshot-primary");
        repository.setDefaultBranch("draft");
        repository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        var catalogue = mock(SystemRepositoryService.class);
        when(catalogue.getPrimaryRepository()).thenReturn(repository);
        return catalogue;
    }
}
