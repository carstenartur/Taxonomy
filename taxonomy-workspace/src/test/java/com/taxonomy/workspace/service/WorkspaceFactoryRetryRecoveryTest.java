package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.CommitProvenance;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Git allocation survives a failed READY save. */
class WorkspaceFactoryRetryRecoveryTest {
    private static final String ORIGINAL = "meta { version: \"original\"; }";
    private static final String NEWER = "meta { version: \"newer\"; }";
    private static final String EDITED = "meta { version: \"edited\"; }";

    @ParameterizedTest
    @ValueSource(strings = {"main", "draft"})
    void retryAdoptsTheOriginalCommitWithoutFollowingANewerSource(String branch) throws Exception {
        try (var fixture = new Fixture(branch)) {
            fixture.failFinalMetadataSave();
            String captured = fixture.workspace.getBaseCommit();
            String allocated = fixture.workspace.getCurrentCommit();
            assertEquals(captured, allocated);
            var destination = fixture.factory.openWorkspaceRepository("retry-workspace");
            assertEquals(1, destination.getDslHistory(branch).size());
            fixture.factory.getCentralRepository("retry-source")
                    .commitDsl("release", NEWER, "source-owner", "Later source change");

            var ready = fixture.manager.provisionWorkspaceRepository("retry-owner", "retry-workspace");

            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertEquals(captured, ready.getBaseCommit());
            assertEquals(allocated, ready.getCurrentCommit());
            assertEquals(branch, ready.getCurrentBranch());
            assertEquals("retry-source", ready.getSourceRepositoryId());
            assertEquals("release", ready.getBaseBranch());
            assertEquals(ORIGINAL, destination.getDslAtHead(branch));
            assertEquals(allocated, destination.getHeadCommit(branch));
            assertEquals(1, destination.getDslHistory(branch).size());
            assertEquals(List.of(branch), destination.getBranchNames());
        }
    }

    @Test
    void retryPreservesAnInterruptedLegacyRootCommit() throws Exception {
        try (var fixture = new Fixture("main", false)) {
            var source = fixture.factory.getCentralRepository("retry-source");
            String captured = source.getHeadCommit("release");
            var destination = fixture.factory.openWorkspaceRepository("retry-workspace");
            var evidence = CommitProvenance.capture("fork", source, "retry-source", "release", captured, null);
            String oldSeed = destination.commitDsl("main", ORIGINAL, "retry-owner", "Legacy content copy", evidence);
            fixture.workspace.setBaseCommit(captured);
            fixture.workspace.setBaseBranch("release");
            fixture.workspace.setSourceBranch(null);
            fixture.source.setDefaultBranch("new-default");
            fixture.workspace.setCurrentCommit(oldSeed);
            fixture.workspace.setProvisioningStatus(WorkspaceProvisioningStatus.FAILED);

            var ready = fixture.manager.provisionWorkspaceRepository("retry-owner", "retry-workspace");

            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertEquals(captured, ready.getBaseCommit());
            assertEquals("release", ready.getSourceBranch());
            assertEquals(oldSeed, ready.getCurrentCommit());
            assertEquals(oldSeed, destination.getHeadCommit("main"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modifiedDestinationIsPreservedEvenWhenMetadataNamesItsNewHead(boolean metadataFollowsEdit) throws Exception {
        try (var fixture = new Fixture("main")) {
            fixture.failFinalMetadataSave();
            String captured = fixture.workspace.getBaseCommit();
            var destination = fixture.factory.openWorkspaceRepository("retry-workspace");
            String editDsl = metadataFollowsEdit ? ORIGINAL : EDITED;
            String edit = destination.commitDsl("main", editDsl, "retry-owner", "Unsaved recovery edit");
            if (metadataFollowsEdit) fixture.workspace.setCurrentCommit(edit);

            assertThrows(RuntimeException.class,
                    () -> fixture.manager.provisionWorkspaceRepository("retry-owner", "retry-workspace"));

            assertEquals(WorkspaceProvisioningStatus.FAILED, fixture.workspace.getProvisioningStatus());
            assertEquals(captured, fixture.workspace.getBaseCommit());
            assertEquals(edit, destination.getHeadCommit("main"));
            assertEquals(editDsl, destination.getDslAtHead("main"));
            assertEquals(2, destination.getDslHistory("main").size());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DslGitRepositoryFactory factory = new DslGitRepositoryFactory(null);
        final SystemRepository source = new SystemRepository();
        final UserWorkspace workspace = new UserWorkspace();
        final WorkspaceManager manager;

        Fixture(String branch) throws Exception {
            this(branch, true);
        }

        Fixture(String branch, boolean failReadySave) throws Exception {
            source.setRepositoryId("retry-source");
            source.setDefaultBranch("release");
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getRepository("retry-source")).thenReturn(source);
            when(catalog.getPrimaryRepository()).thenReturn(source);
            factory.getCentralRepository("retry-source")
                    .commitDsl("release", ORIGINAL, "source-owner", "Original source");
            workspace.setWorkspaceId("retry-workspace");
            workspace.setUsername("retry-owner");
            workspace.setSourceRepositoryId("retry-source");
            workspace.setSourceBranch("release");
            workspace.setCurrentBranch(branch);
            workspace.setCreatedAt(Instant.now());
            workspace.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
            var rows = mock(UserWorkspaceRepository.class);
            when(rows.findByWorkspaceId("retry-workspace")).thenReturn(Optional.of(workspace));
            when(rows.claimProvisioning(anyString(), anyString(),
                    eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection())).thenReturn(1);
            var failReadyOnce = new AtomicBoolean(failReadySave);
            when(rows.save(any(UserWorkspace.class))).thenAnswer(call -> {
                UserWorkspace value = call.getArgument(0);
                if (value.getProvisioningStatus() == WorkspaceProvisioningStatus.READY
                        && failReadyOnce.compareAndSet(true, false)) {
                    throw new IllegalStateException("Simulated metadata outage after Git commit");
                }
                return value;
            });
            manager = new WorkspaceManager(rows, 50, catalog, factory);
        }

        void failFinalMetadataSave() {
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> manager.provisionWorkspaceRepository("retry-owner", "retry-workspace"));
            assertEquals("Simulated metadata outage after Git commit", error.getCause().getMessage());
            assertEquals(WorkspaceProvisioningStatus.FAILED, workspace.getProvisioningStatus());
            assertNotNull(workspace.getBaseCommit());
            assertNotNull(workspace.getCurrentCommit());
        }

        @Override public void close() throws Exception { factory.close(); }
    }
}
