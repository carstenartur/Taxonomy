package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Actual JGit source/target repositories; an absent source is not an empty valid checkpoint. */
class WorkspaceProvisioningSourceTest {
    enum Storage { ISOLATED, LEGACY }

    @ParameterizedTest
    @EnumSource(Storage.class)
    void missingSourceFailsAndCanBeRetriedAfterBootstrap(Storage storage) throws Exception {
        try (DslGitRepositoryFactory factory = new DslGitRepositoryFactory(null)) {
            var source = factory.getSystemRepository();
            var row = workspace();
            var manager = manager(storage, factory, row);

            assertThrows(RuntimeException.class,
                    () -> manager.provisionWorkspaceRepository("alice", "source-check"));
            assertEquals(WorkspaceProvisioningStatus.FAILED, row.getProvisioningStatus());
            assertNull(row.getCurrentCommit());
            assertNull(row.getProvisionedAt());
            assertNotNull(row.getProvisioningError());
            assertTrue(factory.openWorkspaceRepository("source-check").getBranchNames().isEmpty());

            String checkpoint = source.commitDsl("draft", "# bootstrapped source", "system", "Bootstrap");
            var result = manager.provisionWorkspaceRepository("alice", "source-check");
            assertEquals(WorkspaceProvisioningStatus.READY, result.getProvisioningStatus());
            assertEquals(checkpoint, result.getBaseCommit());
            assertNotNull(result.getCurrentCommit());
            assertNull(result.getProvisioningError());
            DslGitRepository target = storage == Storage.ISOLATED
                    ? factory.openWorkspaceRepository("source-check") : source;
            assertEquals("# bootstrapped source", target.getDslAtCommit(result.getCurrentCommit()));
        }
    }

    @ParameterizedTest
    @EnumSource(Storage.class)
    void committedEmptyTextIsARealCheckpointNotMissingBootstrap(Storage storage) throws Exception {
        try (DslGitRepositoryFactory factory = new DslGitRepositoryFactory(null)) {
            String checkpoint = factory.getSystemRepository().commitDsl("draft", "", "system", "Empty model");
            var row = workspace();
            var result = manager(storage, factory, row).provisionWorkspaceRepository("alice", "source-check");
            assertEquals(WorkspaceProvisioningStatus.READY, result.getProvisioningStatus());
            assertEquals(checkpoint, result.getBaseCommit());
            assertNotNull(result.getCurrentCommit());
        }
    }

    private static WorkspaceManager manager(Storage storage, DslGitRepositoryFactory factory, UserWorkspace row) {
        var rows = mock(UserWorkspaceRepository.class);
        when(rows.findByWorkspaceId("source-check")).thenReturn(Optional.of(row));
        when(rows.save(any(UserWorkspace.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var repositories = mock(SystemRepositoryService.class);
        var primary = new SystemRepository();
        primary.setRepositoryId("primary-source");
        primary.setDefaultBranch("draft");
        when(repositories.getPrimaryRepository()).thenReturn(primary);
        return storage == Storage.ISOLATED
                ? new WorkspaceManager(rows, 10, repositories, factory)
                : new WorkspaceManager(rows, 10, repositories, factory.getSystemRepository());
    }

    private static UserWorkspace workspace() {
        var row = new UserWorkspace();
        row.setWorkspaceId("source-check");
        row.setUsername("alice");
        row.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        return row;
    }
}
