package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.CommitProvenance;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepositoryWorkspaceSnapshotTest {
    @Test
    void malformedSourceEvidenceIsNotCopiedIntoWorkingCopyFailureMetadata() throws Exception {
        String privatePayload = "private-provisioning-document-742";
        try (var factory = new DslGitRepositoryFactory(null)) {
            var source = factory.getCentralRepository("source");
            String captured = malformedProvenance(source, privatePayload);
            var metadata = new SystemRepository(); metadata.setRepositoryId("source"); metadata.setDefaultBranch("approved");
            var catalogue = mock(SystemRepositoryService.class);
            when(catalogue.getRepository("source")).thenReturn(metadata);
            var retained = new java.util.concurrent.atomic.AtomicReference<UserWorkspace>();
            var rows = mock(UserWorkspaceRepository.class);
            when(rows.save(any(UserWorkspace.class))).thenAnswer(call -> {
                UserWorkspace row = call.getArgument(0);
                retained.set(row);
                return row;
            });
            var memberships = mock(RepositoryMembershipService.class);
            when(memberships.canContribute(metadata, "owner")).thenReturn(true);
            var service = new RepositoryWorkspaceService(rows, catalogue, factory, memberships);
            service.configureActorAccess(WorkspaceProvisioningTestClaims.fixtureOwners());

            var failure = assertThrows(IllegalStateException.class,
                    () -> service.createWorkingCopy("owner", "source", "approved", "Working copy", null));

            // The real parser reads a malformed native Git blob; no injected exception supplies the message.
            assertInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains(privatePayload));
            UserWorkspace row = retained.get();
            assertNotNull(row);
            assertEquals(WorkspaceProvisioningStatus.FAILED, row.getProvisioningStatus());
            assertEquals("source", row.getSourceRepositoryId());
            assertEquals("approved", row.getSourceBranch());
            assertFalse(row.getProvisioningError().contains(privatePayload));
            assertFalse(row.getProvisioningError().contains("com.fasterxml.jackson"));
            assertFalse(row.getProvisioningError().isBlank());
            assertTrue(row.getProvisioningError().length() <= 255);
            assertNull(row.getCurrentCommit());
            assertEquals(captured, source.getHeadCommit("approved"));
            assertNull(factory.openWorkspaceRepository(row.getWorkspaceId()).getHeadCommit("draft"));
        }
    }

    private static String malformedProvenance(DslGitRepository source, String privatePayload) throws Exception {
        var git = source.getGitRepository();
        try (var writer = git.newObjectInserter()) {
            var tree = new org.eclipse.jgit.lib.TreeFormatter();
            tree.append(CommitProvenance.FILENAME, org.eclipse.jgit.lib.FileMode.REGULAR_FILE,
                    writer.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB,
                            ("{\"version\":\"" + privatePayload + "\",\"operation\":\"copy\",\"sources\":[]}")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            tree.append(DslGitRepository.DSL_FILENAME, org.eclipse.jgit.lib.FileMode.REGULAR_FILE,
                    writer.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB,
                            "meta { version: \"original\"; }".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var commit = new org.eclipse.jgit.lib.CommitBuilder();
            commit.setTreeId(writer.insert(tree));
            commit.setAuthor(new org.eclipse.jgit.lib.PersonIdent("source-owner", "source-owner@example.test"));
            commit.setCommitter(commit.getAuthor());
            commit.setMessage("Historical snapshot with malformed provenance");
            var id = writer.insert(commit);
            writer.flush();
            var update = git.updateRef("refs/heads/approved");
            update.setExpectedOldObjectId(org.eclipse.jgit.lib.ObjectId.zeroId());
            update.setNewObjectId(id);
            assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.NEW, update.update());
            return id.name();
        }
    }

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
            service.configureActorAccess(WorkspaceProvisioningTestClaims.fixtureOwners());
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
