package com.taxonomy.workspace.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** First synchronization must use the fork point, not the source's newer HEAD. */
class WorkspaceInitialSyncBaseTest {
    private static final String BASE = "meta { language: \"taxdsl\"; version: \"base\"; }";
    private static final String OURS = "meta { language: \"taxdsl\"; version: \"local\"; }";
    private static final String THEIRS = "meta { language: \"taxdsl\"; version: \"remote\"; }";
    private static final String MERGED = "meta { language: \"taxdsl\"; version: \"merged\"; }";
    private static final WorkspaceArchitectureVersionPort DIRECT_VERSIONS = new WorkspaceArchitectureVersionPort() {
        @Override
        public <T> T version(RepositoryContext context, String rationale, GitAction<T> action) throws IOException {
            return action.run();
        }
    };

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initialPullAndPublishRetainTheRecordedForkPoint(boolean publish) throws Exception {
        try (var factory = spy(new DslGitRepositoryFactory(null))) {
            var source = factory.getSystemRepository();
            String captured = source.commitDsl("draft", BASE, "system", "Original snapshot");
            doReturn(source).when(factory).getCentralRepository("fork-source");
            var metadata = new SystemRepository();
            metadata.setRepositoryId("fork-source");
            metadata.setDefaultBranch("draft");
            metadata.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getPrimaryRepository()).thenReturn(metadata);
            when(catalog.getRepository("fork-source")).thenReturn(metadata);
            var workspace = new UserWorkspace();
            workspace.setWorkspaceId("initial-sync");
            workspace.setUsername("fork-owner");
            workspace.setCreatedAt(Instant.now());
            workspace.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
            var rows = mock(UserWorkspaceRepository.class);
            when(rows.findByWorkspaceId("initial-sync")).thenReturn(Optional.of(workspace));
            when(rows.claimProvisioning(anyString(), anyString(),
                    eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection())).thenReturn(1);
            when(rows.save(any(UserWorkspace.class))).thenAnswer(call -> call.getArgument(0));
            var manager = new WorkspaceManager(rows, 50, catalog, factory);
            manager.provisionWorkspaceRepository("fork-owner", "initial-sync");
            assertEquals(captured, workspace.getBaseCommit());
            var workingCopy = factory.openWorkspaceRepository("initial-sync");
            assertEquals(List.of("main"), workingCopy.getBranchNames());

            // Independent local and remote edits happen before the very first sync.
            workingCopy.commitDsl("main", OURS, "fork-owner", "Local edit");
            source.commitDsl("draft", THEIRS, "remote-owner", "Remote edit");
            var merger = mock(SemanticGitMergeService.class);
            when(merger.mergeContent(anyString(), anyString(), anyString()))
                    .thenReturn(new TaxDslMergeResult(MERGED, List.of()));
            var syncStates = mock(SyncStateRepository.class);
            var state = new SyncState();
            state.setUsername("fork-owner");
            when(syncStates.findByUsername("fork-owner")).thenReturn(Optional.of(state));
            var portfolio = mock(WorkspacePortfolioGitPort.class);
            var service = new GitNativeSyncIntegrationService(syncStates, rows, catalog, factory,
                    merger, portfolio, mock(WorkspaceContextResolver.class), DIRECT_VERSIONS);

            if (publish) service.publishFromWorkspaceToShared("fork-owner", "initial-sync");
            else service.syncFromSharedToWorkspace("fork-owner", "initial-sync");

            verify(merger).mergeContent(BASE, publish ? THEIRS : OURS, publish ? OURS : THEIRS);
            assertEquals(MERGED, workingCopy.getDslAtHead("main"));
            assertEquals(MERGED, workingCopy.getDslAtHead("sync-base"));
            assertEquals(captured, workspace.getBaseCommit());
            assertEquals(workingCopy.getHeadCommit("main"), workspace.getCurrentCommit());
            if (publish) assertEquals(MERGED, source.getDslAtHead("draft"));
            else assertEquals(THEIRS, source.getDslAtHead("draft"));
        }
    }
}
