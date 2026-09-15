package com.taxonomy.workspace.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.*;
import com.taxonomy.workspace.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitNativeSyncSnapshotRaceTest {
    private static final WorkspaceArchitectureVersionPort DIRECT = new WorkspaceArchitectureVersionPort() {
        public <T> T version(RepositoryContext context, String rationale, GitAction<T> action) throws IOException {
            return action.run();
        }
    };
    static Stream<Arguments> races() {
        return Stream.of(false, true).flatMap(publish -> Stream.of(false, true).flatMap(source ->
                Stream.of(false, true).map(noOp -> Arguments.of(publish, source, noOp))));
    }
    @ParameterizedTest
    @MethodSource("races")
    void rejectsConcurrentSourceAndWorkspaceHeadsIncludingSemanticNoOps(boolean publish, boolean changeSource, boolean noOp) throws Exception {
        try (var fixture = new Fixture()) {
            String beforeSource = fixture.source.getHeadCommit("draft");
            String beforeLocal = fixture.local.getHeadCommit("main");
            String beforeBase = fixture.local.getHeadCommit("sync-base");
            when(fixture.merger.mergeContent(anyString(), anyString(), anyString())).thenAnswer(call -> {
                (changeSource ? fixture.source : fixture.local).commitDsl(changeSource ? "draft" : "main", "winner", "other", "Concurrent writer");
                return new TaxDslMergeResult(noOp ? (publish ? "remote" : "local") : "merged", List.of());
            });
            assertThrows(BranchHeadConflictException.class, () -> fixture.sync(publish));
            assertEquals(changeSource ? "winner" : "remote", fixture.source.getDslAtHead("draft"));
            assertEquals(changeSource ? "local" : "winner", fixture.local.getDslAtHead("main"));
            assertEquals(beforeBase, fixture.local.getHeadCommit("sync-base"));
            if (changeSource) assertEquals(beforeLocal, fixture.local.getHeadCommit("main"));
            else assertEquals(beforeSource, fixture.source.getHeadCommit("draft"));
            verify(fixture.rows, never()).save(any());
            verify(fixture.portfolio, never()).materializePortfolio(anyString(), anyString(), any());
            assertNull(fixture.workspace.getLastIntegratedCommit());
        }
    }
    @Test
    void sourceMovementAfterLocalWriteCannotBeRecordedAsIntegrated() throws Exception {
        try (var fixture = new Fixture()) {
            String captured = fixture.source.getHeadCommit("draft");
            when(fixture.merger.mergeContent(anyString(), anyString(), anyString()))
                    .thenReturn(new TaxDslMergeResult("merged", List.of()));
            doAnswer(call -> {
                fixture.source.commitDsl("draft", "later", "other", "After local write");
                return null;
            }).when(fixture.portfolio).materializePortfolio(anyString(), anyString(), any());
            fixture.sync(false);
            assertEquals(captured, fixture.workspace.getLastIntegratedCommit());
            assertEquals(captured, fixture.workspace.getLastFetchedCommit());
            assertEquals("later", fixture.source.getDslAtHead("draft"));
            assertEquals("merged", fixture.local.getDslAtHead("main"));
        }
    }
    private static final class Fixture implements AutoCloseable {
        final DslGitRepositoryFactory factory = spy(new DslGitRepositoryFactory(null));
        final DslGitRepository source = factory.getSystemRepository();
        final DslGitRepository local = factory.openWorkspaceRepository("workspace");
        final UserWorkspace workspace = new UserWorkspace();
        final UserWorkspaceRepository rows = mock(UserWorkspaceRepository.class);
        final WorkspacePortfolioGitPort portfolio = mock(WorkspacePortfolioGitPort.class);
        final SemanticGitMergeService merger = mock(SemanticGitMergeService.class);
        final GitNativeSyncIntegrationService service;
        Fixture() throws Exception {
            source.commitDsl("draft", "remote", "owner", "Remote");
            local.commitDsl("main", "local", "owner", "Local");
            local.commitDsl("sync-base", "base", "owner", "Base");
            doReturn(source).when(factory).getCentralRepository("source");
            workspace.setWorkspaceId("workspace"); workspace.setUsername("owner");
            workspace.setSourceRepositoryId("source"); workspace.setCurrentBranch("main");
            when(rows.findByWorkspaceId("workspace")).thenReturn(Optional.of(workspace));
            when(rows.save(any())).thenAnswer(call -> call.getArgument(0));
            var catalog = mock(SystemRepositoryService.class);
            var metadata = new SystemRepository(); metadata.setRepositoryId("source"); metadata.setDefaultBranch("draft");
            when(catalog.getRepository("source")).thenReturn(metadata);
            var syncRows = mock(SyncStateRepository.class);
            when(syncRows.findByUsername("owner")).thenReturn(Optional.of(new SyncState()));
            service = new GitNativeSyncIntegrationService(syncRows, rows, catalog, factory, merger, portfolio,
                    mock(WorkspaceContextResolver.class), DIRECT);
        }
        void sync(boolean publish) throws IOException {
            if (publish) service.publishFromWorkspaceToShared("owner", "workspace");
            else service.syncFromSharedToWorkspace("owner", "workspace");
        }
        public void close() { factory.close(); }
    }
}
