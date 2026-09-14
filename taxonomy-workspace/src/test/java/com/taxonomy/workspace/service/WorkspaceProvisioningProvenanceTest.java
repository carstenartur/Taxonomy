package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Persisted repository identity must survive retries and concurrent source edits. */
class WorkspaceProvisioningProvenanceTest {
    private static final String PRIMARY = "meta { version: \"primary\"; }";
    private static final String CAPTURED = "meta { version: \"selected\"; }";
    private static final String LATER = "meta { version: \"later\"; }";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryUsesThePersistedRepositoryAndItsSelectedOrDefaultBranch(boolean selectedBranch) throws Exception {
        try (var factory = spy(new DslGitRepositoryFactory(null))) {
            String primaryHead = factory.getSystemRepository().commitDsl("draft", PRIMARY, "system", "Primary");
            var source = spy(factory.getCentralRepository("selected-source"));
            doReturn(source).when(factory).getCentralRepository("selected-source");
            String branch = selectedBranch ? "feature/chosen" : "main";
            String captured = source.commitDsl(branch, CAPTURED, "owner", "Selected snapshot");
            doAnswer(call -> {
                String text = (String) call.callRealMethod();
                source.commitDsl(branch, LATER, "other", "Concurrent update");
                return text;
            }).when(source).getDslAtCommit(captured);
            var workspace = workspace();
            workspace.setSourceRepositoryId("selected-source");
            workspace.setSourceBranch(selectedBranch ? branch : null);
            var catalog = catalog();
            when(catalog.getRepository("selected-source")).thenReturn(metadata("selected-source", "main"));
            var manager = new WorkspaceManager(rows(workspace), 50, catalog, factory);

            UserWorkspace ready = manager.provisionWorkspaceRepository("source-owner", workspace.getWorkspaceId());

            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertEquals("selected-source", ready.getSourceRepositoryId());
            assertEquals(branch, ready.getBaseBranch());
            assertEquals(branch, ready.getSyncTargetBranch());
            assertEquals(captured, ready.getBaseCommit());
            assertNotEquals(captured, source.getHeadCommit(branch));
            var destination = factory.openWorkspaceRepository(workspace.getWorkspaceId());
            assertEquals(CAPTURED, destination.getDslAtHead("main"));
            assertEquals(destination.getHeadCommit("main"), ready.getCurrentCommit());
            assertEquals(List.of("main"), destination.getBranchNames());
            assertEquals(primaryHead, factory.getSystemRepository().getHeadCommit("draft"));
            verify(catalog, never()).getPrimaryRepository();
        }
    }

    @Test
    void unavailableRecordedRepositoryFailsWithoutReplacingItsIdentityOrOpeningDestination() throws Exception {
        try (var factory = spy(new DslGitRepositoryFactory(null))) {
            factory.getSystemRepository().commitDsl("draft", PRIMARY, "system", "Primary");
            var workspace = workspace();
            workspace.setSourceRepositoryId("unavailable-source");
            workspace.setSourceBranch("release/saved");
            var catalog = catalog();
            var unavailable = new IllegalStateException("Recorded source is unavailable");
            when(catalog.getRepository("unavailable-source")).thenThrow(unavailable);
            var manager = new WorkspaceManager(rows(workspace), 50, catalog, factory);

            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> manager.provisionWorkspaceRepository("source-owner", workspace.getWorkspaceId()));

            assertSame(unavailable, error.getCause());
            assertEquals(WorkspaceProvisioningStatus.FAILED, workspace.getProvisioningStatus());
            assertEquals("unavailable-source", workspace.getSourceRepositoryId());
            assertEquals("release/saved", workspace.getSourceBranch());
            assertNull(workspace.getCurrentCommit());
            verify(factory, never()).openWorkspaceRepository(anyString());
            verify(catalog, never()).getPrimaryRepository();
        }
    }

    @Test
    void legacyForkUsesTheCapturedCommitInsteadOfAConcurrentSourceHead() throws Exception {
        try (var git = spy(new DslGitRepository())) {
            String captured = git.commitDsl("draft", CAPTURED, "system", "Original snapshot");
            doAnswer(call -> {
                String text = (String) call.callRealMethod();
                git.commitDsl("draft", LATER, "other", "Concurrent update");
                return text;
            }).when(git).getDslAtCommit(captured);
            var workspace = workspace();
            var manager = new WorkspaceManager(rows(workspace), 50, catalog(), git);

            UserWorkspace ready = manager.provisionWorkspaceRepository("source-owner", workspace.getWorkspaceId());

            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertEquals(captured, ready.getBaseCommit());
            assertEquals(captured, ready.getCurrentCommit());
            assertEquals(captured, git.getHeadCommit(ready.getCurrentBranch()));
            assertEquals(CAPTURED, git.getDslAtHead(ready.getCurrentBranch()));
            assertNotEquals(captured, git.getHeadCommit("draft"));
        }
    }

    private static UserWorkspace workspace() {
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId("source-bound-retry");
        workspace.setUsername("source-owner");
        workspace.setCurrentBranch("draft");
        workspace.setCreatedAt(Instant.now());
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.FAILED);
        return workspace;
    }

    private static SystemRepository metadata(String id, String branch) {
        var metadata = new SystemRepository();
        metadata.setRepositoryId(id);
        metadata.setDefaultBranch(branch);
        metadata.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        return metadata;
    }

    private static SystemRepositoryService catalog() {
        var catalog = mock(SystemRepositoryService.class);
        when(catalog.getPrimaryRepository()).thenReturn(metadata("primary-source", "draft"));
        return catalog;
    }

    private static UserWorkspaceRepository rows(UserWorkspace workspace) {
        var rows = mock(UserWorkspaceRepository.class);
        when(rows.findByWorkspaceId(workspace.getWorkspaceId())).thenReturn(Optional.of(workspace));
        when(rows.claimProvisioning(anyString(), anyString(),
                eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection())).thenReturn(1);
        when(rows.save(any(UserWorkspace.class))).thenAnswer(call -> call.getArgument(0));
        return rows;
    }
}
