package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceLegacyRetryRecoveryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesAnExistingCapturedBranchWithoutOverwritingEdits(boolean destinationMoved) throws Exception {
        try (var git = new DslGitRepository()) {
            String captured = git.commitDsl("draft", "original", "owner", "Source");
            var row = new UserWorkspace();
            row.setWorkspaceId("retry"); row.setUsername("owner");
            row.setCurrentBranch("draft"); row.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
            var rows = mock(UserWorkspaceRepository.class);
            when(rows.findByWorkspaceId("retry")).thenReturn(Optional.of(row));
            when(rows.claimProvisioning(eq("retry"), eq("owner"), any(), anyCollection())).thenReturn(1);
            var failOnce = new AtomicBoolean(true);
            when(rows.save(any(UserWorkspace.class))).thenAnswer(call -> {
                if (row.getProvisioningStatus() == WorkspaceProvisioningStatus.READY && failOnce.getAndSet(false))
                    throw new DataAccessResourceFailureException("temporary metadata save failure");
                return call.getArgument(0);
            });
            var metadata = new SystemRepository(); metadata.setRepositoryId("source"); metadata.setDefaultBranch("draft");
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getPrimaryRepository()).thenReturn(metadata);
            when(catalog.getRepository("source")).thenReturn(metadata);
            var manager = new WorkspaceManager(rows, 10, catalog, git);
            assertThrows(RuntimeException.class, () -> manager.provisionWorkspaceRepository("owner", "retry"));
            assertEquals(WorkspaceProvisioningStatus.FAILED, row.getProvisioningStatus());
            String destination = "owner/workspace/retry";
            assertEquals(captured, git.getHeadCommit(destination));
            git.commitDsl("draft", "newer source", "other", "Advance source before retry");
            String target = destinationMoved ? git.commitDsl(destination, "user edit", "owner", "Keep this edit") : captured;
            if (destinationMoved) {
                assertThrows(RuntimeException.class, () -> manager.provisionWorkspaceRepository("owner", "retry"));
                assertEquals(WorkspaceProvisioningStatus.FAILED, row.getProvisioningStatus());
            } else {
                assertEquals(WorkspaceProvisioningStatus.READY,
                        assertDoesNotThrow(() -> manager.provisionWorkspaceRepository("owner", "retry")).getProvisioningStatus());
                assertEquals(captured, row.getBaseCommit());
                assertEquals(captured, row.getCurrentCommit());
            }
            assertEquals(target, git.getHeadCommit(destination));
            assertEquals(destinationMoved ? 2 : 1, git.getDslHistory(destination).size());
        }
    }
}
