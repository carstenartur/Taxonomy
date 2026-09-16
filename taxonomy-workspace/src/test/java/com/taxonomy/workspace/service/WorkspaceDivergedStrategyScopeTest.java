package com.taxonomy.workspace.service;

import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.SyncIntegrationService.DivergedStrategy;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceDivergedStrategyScopeTest {
    private static final String PRIMARY = "meta { version: \"untouched-primary\"; }";
    private static final String LOCAL = "meta { version: \"local-choice\"; }";
    private static final String REMOTE = "meta { version: \"remote-choice\"; }";
    private static final WorkspaceArchitectureVersionPort DIRECT = new WorkspaceArchitectureVersionPort() {
        @Override public <T> T version(RepositoryContext context, String rationale, GitAction<T> action) throws IOException {
            return action.run();
        }
    };

    @ParameterizedTest
    @CsvSource({"KEEP_MINE,true", "TAKE_SHARED,true", "KEEP_MINE,false", "TAKE_SHARED,false"})
    void chosenSideAffectsOnlyTheSelectedRepository(DivergedStrategy strategy, boolean isolated) throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var primary = factory.getSystemRepository();
            String primaryHead = primary.commitDsl("draft", PRIMARY, "primary-owner", "Primary seed");
            primary.createBranchAtCommit("work", primaryHead);
            var source = factory.getCentralRepository("selected-source");
            String remoteHead = source.commitDsl("release", REMOTE, "source-owner", "Remote change");
            var local = isolated ? factory.openWorkspaceRepository("selected-workspace") : source;
            local.commitDsl("work", LOCAL, "selected-user", "Local change");
            var sourceMetadata = new SystemRepository();
            sourceMetadata.setRepositoryId("selected-source");
            sourceMetadata.setDefaultBranch("release");
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getRepository("selected-source")).thenReturn(sourceMetadata);
            when(catalog.getSharedBranch()).thenReturn("draft");
            var workspace = new UserWorkspace();
            workspace.setWorkspaceId("selected-workspace");
            workspace.setUsername("selected-user");
            workspace.setSourceRepositoryId("selected-source");
            workspace.setSourceBranch("release");
            workspace.setCurrentBranch("work");
            workspace.setBaseCommit(remoteHead);
            var rows = mock(UserWorkspaceRepository.class);
            when(rows.findByWorkspaceId("selected-workspace")).thenReturn(Optional.of(workspace));
            var contexts = mock(WorkspaceContextResolver.class);
            var context = isolated
                    ? RepositoryContext.workspace("selected-source", "selected-workspace", "work", "selected-user")
                    : RepositoryContext.centralWrite("selected-source", "work", "selected-user");
            when(contexts.resolveRepositoryContextForUser("selected-user")).thenReturn(context);
            var syncRows = mock(SyncStateRepository.class);
            when(syncRows.findByUsername("selected-user")).thenReturn(Optional.of(new SyncState()));
            var portfolio = mock(WorkspacePortfolioGitPort.class);
            var semantic = mock(SemanticGitMergeService.class);
            var service = new GitNativeSyncIntegrationService(syncRows, rows, catalog, factory,
                    semantic, portfolio, contexts, DIRECT);

            assertDoesNotThrow(() -> service.resolveDiverged("selected-user", "work", strategy));

            String selected = strategy == DivergedStrategy.KEEP_MINE ? LOCAL : REMOTE;
            assertEquals(selected, local.getDslAtHead("work"));
            assertEquals(selected, source.getDslAtHead("release"));
            assertEquals(primaryHead, primary.getHeadCommit("draft"));
            assertEquals(primaryHead, primary.getHeadCommit("work"));
            assertEquals(PRIMARY, primary.getDslAtHead("draft"));
            if (isolated) {
                assertEquals(selected, local.getDslAtHead("sync-base"));
                assertEquals(local.getHeadCommit("work"), workspace.getCurrentCommit());
                verify(rows).save(workspace);
            } else {
                verifyNoInteractions(rows);
            }
            verifyNoInteractions(semantic);
            String materializedBranch = !isolated && strategy == DivergedStrategy.KEEP_MINE ? "release" : "work";
            verify(portfolio).materializePortfolio(selected, "selected-user",
                    new WorkspaceContext("selected-user", isolated ? "selected-workspace" : null,
                            materializedBranch, "selected-source"));
        }
    }
}
