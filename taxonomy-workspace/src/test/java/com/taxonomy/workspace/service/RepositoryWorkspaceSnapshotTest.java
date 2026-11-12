package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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
    void centralForkRejectsAChangedExplicitSourceBranchBeforePublishing() throws Exception {
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
            fork.setUpstreamRepositoryId("source"); fork.setUpstreamBranch("approved"); fork.setForkPointCommit(captured);
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getRepository("source")).thenReturn(metadata);
            when(catalog.createForkMetadata(eq("source"), eq("approved"), eq(captured), anyString(), anyString(),
                    isNull(), any(), eq("owner"))).thenReturn(fork);
            var memberships = mock(RepositoryMembershipService.class);
            when(memberships.canContribute(metadata, "owner")).thenReturn(true);
            var service = new ArchitectureRepositoryProvisioningService(catalog, memberships, factory, namespace -> "meta { language: \"taxdsl\"; }\n");
            assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                    () -> service.createFork("source", "approved", "Fork", "fork", null, RepositoryVisibility.PRIVATE, "owner"))
                    .getStatusCode());
            var destination = factory.getCentralRepository("fork");
            assertNull(destination.getHeadCommit("main"));
            verify(catalog).createForkMetadata("source", "approved", captured, "Fork", "fork", null, RepositoryVisibility.PRIVATE, "owner");
            verify(catalog, never()).markProvisioningReady("fork");
            assertNotEquals(captured, source.getHeadCommit("approved"));
        }
    }
}
