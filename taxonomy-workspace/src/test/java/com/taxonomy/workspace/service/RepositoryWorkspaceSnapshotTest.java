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
    void workingCopyPreservesTheOriginalCapturedCommitAndItsParents() throws Exception {
        try (var factory = spy(new DslGitRepositoryFactory(null))) {
            var source = spy(factory.getSystemRepository());
            doReturn(source).when(factory).getCentralRepository("source");
            String parent = source.commitDsl("approved", "initial content", "owner", "Initial");
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
            var memberships = mock(RepositoryMembershipService.class);
            when(memberships.canContribute(metadata, "owner")).thenReturn(true);
            doAnswer(call -> {
                call.<java.util.function.Consumer<SystemRepository>>getArgument(1).accept(metadata);
                return null;
            }).when(catalog).readCurrentAuthorization(eq("source"), any());
            WorkspaceProvisioningTestClaims.enable(rows);
            var service = new RepositoryWorkspaceService(rows, catalog, factory, memberships);
            var result = service.createWorkingCopy("owner", "source", "approved", "Working copy", null);
            var destination = factory.openWorkspaceRepository(result.getWorkspaceId());
            assertEquals(captured, result.getBaseCommit());
            assertEquals("captured content", destination.getDslAtHead("draft"));
            assertEquals(captured, result.getCurrentCommit());
            assertEquals(captured, destination.getHeadCommit("draft"));
            assertEquals(captured, destination.getHeadCommit("sync-base"));
            assertEquals("source", result.getSourceRepositoryId());
            assertEquals("approved", result.getSourceBranch());
            assertTrue(destination.isAncestor(parent, captured));
            assertEquals("initial content", destination.getDslAtCommit(parent));
            assertEquals(2, destination.getDslHistory("draft").size());
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
