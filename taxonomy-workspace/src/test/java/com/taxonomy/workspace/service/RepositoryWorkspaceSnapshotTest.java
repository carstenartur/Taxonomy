package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepositoryWorkspaceSnapshotTest {
    @Test
    void workingCopyContentAndProvenanceUseOneCapturedCommit() throws Exception {
        try (var factory = spy(new DslGitRepositoryFactory(null))) {
            var source = spy(factory.getSystemRepository());
            doReturn(source).when(factory).getCentralRepository("source");
            String captured = source.commitDsl("approved", "captured content", "owner", "Captured");
            var first = new AtomicBoolean(true);
            doAnswer(call -> {
                String head = (String) call.callRealMethod();
                if (first.getAndSet(false)) source.commitDsl("approved", "newer content", "other", "Concurrent change");
                return head;
            }).when(source).getHeadCommit("approved");
            var metadata = new SystemRepository(); metadata.setRepositoryId("source"); metadata.setDefaultBranch("approved");
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getRepository("source")).thenReturn(metadata);
            var rows = mock(UserWorkspaceRepository.class);
            when(rows.save(any(UserWorkspace.class))).thenAnswer(call -> call.getArgument(0));
            var service = new RepositoryWorkspaceService(rows, catalog, factory);
            var result = service.createWorkingCopy("owner", "source", "approved", "Working copy", null);
            var destination = factory.openWorkspaceRepository(result.getWorkspaceId());
            assertEquals(captured, result.getBaseCommit());
            assertEquals("captured content", destination.getDslAtHead("draft"));
            assertEquals(result.getCurrentCommit(), destination.getHeadCommit("draft"));
            assertEquals(result.getCurrentCommit(), destination.getHeadCommit("sync-base"));
            assertEquals(1, destination.getDslHistory("draft").size());
            var evidence = destination.getProvenance(result.getCurrentCommit());
            assertEquals("fork", evidence.operation());
            assertEquals("source", evidence.sources().getFirst().repositoryId());
            assertEquals("approved", evidence.sources().getFirst().branch());
            assertEquals(captured, evidence.sources().getFirst().commitId());
            assertEquals(org.eclipse.jgit.lib.ObjectId.fromString(captured),
                    new org.eclipse.jgit.lib.ObjectInserter.Formatter().idFor(
                            org.eclipse.jgit.lib.Constants.OBJ_COMMIT, evidence.sources().getFirst().rawCommit()));
            assertNotEquals(captured, source.getHeadCommit("approved"));
        }
    }

    @Test
    void centralForkContentMetadataAndEvidenceUseOneCapturedCommit() throws Exception {
        try (var factory = spy(new DslGitRepositoryFactory(null))) {
            var source = spy(factory.getSystemRepository());
            doReturn(source).when(factory).getCentralRepository("source");
            String captured = source.commitDsl("approved", "captured fork content", "owner", "Captured");
            var first = new AtomicBoolean(true);
            doAnswer(call -> {
                String head = (String) call.callRealMethod();
                if (first.getAndSet(false)) source.commitDsl("approved", "newer fork content", "other", "Concurrent change");
                return head;
            }).when(source).getHeadCommit("approved");
            var metadata = new SystemRepository(); metadata.setRepositoryId("source"); metadata.setDefaultBranch("approved");
            var fork = new SystemRepository(); fork.setRepositoryId("fork"); fork.setStorageRepositoryName("central-fork"); fork.setDefaultBranch("main");
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getRepository("source")).thenReturn(metadata);
            when(catalog.createForkMetadata(eq("source"), eq("approved"), eq(captured), anyString(), anyString(),
                    isNull(), any(), eq("owner"))).thenReturn(fork);
            when(catalog.markProvisioningReady("fork")).thenReturn(fork);
            var service = new ArchitectureRepositoryProvisioningService(catalog, mock(RepositoryMembershipService.class), factory);
            assertSame(fork, service.createFork("source", "approved", "Fork", "fork", null, RepositoryVisibility.PRIVATE, "owner"));
            var destination = factory.getCentralRepository("fork");
            assertEquals("captured fork content", destination.getDslAtHead("main"));
            var evidence = destination.getProvenance(destination.getHeadCommit("main"));
            assertEquals("fork", evidence.operation());
            assertEquals(captured, evidence.sources().getFirst().commitId());
            verify(catalog).createForkMetadata("source", "approved", captured, "Fork", "fork", null, RepositoryVisibility.PRIVATE, "owner");
            assertNotEquals(captured, source.getHeadCommit("approved"));
        }
    }
}
