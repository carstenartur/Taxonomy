package com.taxonomy.workspace.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.*;
import com.taxonomy.workspace.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceGitCasBoundaryTest {
    static final String BASE = "requirement R0 { text: \"base\"; }";
    static final String MERGED = "requirement R0 { text: \"merged\"; }";
    static final String CONCURRENT = "requirement OTHER { text: \"concurrent owner\"; }";
    static final WorkspaceArchitectureVersionPort DIRECT = new WorkspaceArchitectureVersionPort() {
        public <T> T version(RepositoryContext context, String rationale, GitAction<T> action) throws IOException {
            return action.run();
        }
    };
    /** Inject an independent commit after the service read but immediately before its write. */
    static final class RacingRepository extends DslGitRepository {
        String armedBranch;
        boolean fired;
        private void race(String branch) throws IOException {
            if (branch.equals(armedBranch) && !fired) {
                fired = true;
                super.commitDsl(branch, CONCURRENT, "other", "Independent concurrent update");
            }
        }
        @Override public String commitDsl(String b, String d, String a, String m) throws IOException {
            race(b); return super.commitDsl(b,d,a,m);
        }
        @Override public String commitDslIfHeadMatches(String b, String h, String d, String a, String m) throws IOException {
            race(b); return super.commitDslIfHeadMatches(b,h,d,a,m);
        }
    }
    static final class Fixture implements AutoCloseable {
        final DslGitRepository source = new DslGitRepository();
        final RacingRepository destination = new RacingRepository();
        final UserWorkspaceRepository rows = mock(UserWorkspaceRepository.class);
        final SyncStateRepository states = mock(SyncStateRepository.class);
        final SystemRepositoryService catalog = mock(SystemRepositoryService.class);
        final DslGitRepositoryFactory repositories = mock(DslGitRepositoryFactory.class);
        final SemanticGitMergeService merger = mock(SemanticGitMergeService.class);
        final WorkspacePortfolioGitPort portfolio = mock(WorkspacePortfolioGitPort.class);
        final WorkspaceContextResolver resolver = mock(WorkspaceContextResolver.class);
        final UserWorkspace workspace = new UserWorkspace();
        final GitNativeSyncIntegrationService service;
        Fixture() throws IOException {
            var repo = new SystemRepository(); repo.setRepositoryId("source"); repo.setSlug("source");
            repo.setDefaultBranch("draft"); repo.setLifecycleState(RepositoryLifecycleState.ACTIVE);
            repo.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
            String base = source.commitDsl("draft", BASE, "source", "Source checkpoint");
            workspace.setWorkspaceId("workspace"); workspace.setUsername("alice");
            workspace.setSourceRepositoryId("source"); workspace.setSourceBranch("draft");
            workspace.setSyncTargetBranch("draft"); workspace.setCurrentBranch("main");
            workspace.setBaseCommit(base); workspace.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
            when(rows.findByWorkspaceId("workspace")).thenReturn(Optional.of(workspace));
            when(rows.findByUsernameAndSharedFalse("alice")).thenReturn(Optional.of(workspace));
            when(rows.claimProvisioning(eq("workspace"),eq("alice"),any(),any())).thenReturn(1);
            when(rows.save(any())).thenAnswer(c -> c.getArgument(0));
            when(states.findByUsername("alice")).thenReturn(Optional.of(new SyncState()));
            when(states.save(any())).thenAnswer(c -> c.getArgument(0));
            when(catalog.getRepository("source")).thenReturn(repo); when(catalog.getPrimaryRepository()).thenReturn(repo);
            when(repositories.getSystemRepository()).thenReturn(source);
            when(repositories.getCentralRepository("source")).thenReturn(source);
            when(repositories.openWorkspaceRepository("workspace")).thenReturn(destination);
            when(resolver.resolveRepositoryContextForUser("alice"))
                .thenReturn(RepositoryContext.workspace("source","workspace","main","alice"));
            when(merger.mergeContent(anyString(),anyString(),anyString()))
                .thenReturn(new TaxDslMergeResult(MERGED,List.of()));
            service = new GitNativeSyncIntegrationService(states,rows,catalog,repositories,merger,portfolio,resolver,DIRECT);
        }
        void initialized() throws IOException {
            destination.commitDsl("main",BASE,"alice","Working branch");
            destination.commitDsl("sync-base",BASE,"alice","Tracking checkpoint");
        }
        public void close() { destination.close(); source.close(); }
    }
    @ParameterizedTest @ValueSource(strings={"main","sync-base"})
    void initializationCannotReplaceAConcurrentCreator(String branch) throws Exception {
        try (var f = new Fixture()) {
            f.destination.armedBranch = branch;
            assertThrows(Exception.class, () -> f.service.syncFromShared("alice","main"));
            assertTrue(f.destination.fired);
            assertEquals(CONCURRENT,f.destination.getDslAtHead(branch));
            verify(f.portfolio,never()).materializePortfolio(anyString(),anyString(),any());
        }
    }
    @Test void trackingWriteCannotOverwriteAConcurrentSynchronization() throws Exception {
        try (var f = new Fixture()) {
            f.initialized(); f.destination.armedBranch = "sync-base";
            assertThrows(Exception.class, () -> f.service.syncFromShared("alice","main"));
            assertTrue(f.destination.fired);
            assertEquals(CONCURRENT,f.destination.getDslAtHead("sync-base"));
            verify(f.portfolio,never()).materializePortfolio(anyString(),anyString(),any());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void trackingSnapshotMustStayCurrentWhileTheMergeIsComputed(boolean unchanged) throws Exception {
        try (var f = new Fixture()) {
            f.initialized(); String oldMain = f.destination.getHeadCommit("main");
            when(f.merger.mergeContent(anyString(),anyString(),anyString())).thenAnswer(call -> {
                f.destination.commitDsl("sync-base",CONCURRENT,"other","New tracking checkpoint");
                return new TaxDslMergeResult(unchanged ? BASE : MERGED,List.of());
            });
            assertThrows(Exception.class, () -> f.service.syncFromShared("alice","main"));
            assertEquals(CONCURRENT,f.destination.getDslAtHead("sync-base"));
            assertEquals(oldMain,f.destination.getHeadCommit("main"));
            verify(f.portfolio,never()).materializePortfolio(anyString(),anyString(),any());
        }
    }
    @Test void provisioningCannotOverwriteAConcurrentAllocation() throws Exception {
        try (var f = new Fixture()) {
            f.destination.armedBranch="main";
            var manager = new WorkspaceManager(f.rows,50,f.catalog,f.repositories);
            assertThrows(RuntimeException.class, () -> manager.provisionWorkspaceRepository("alice","workspace"));
            assertTrue(f.destination.fired);
            assertEquals(CONCURRENT,f.destination.getDslAtHead("main"));
            assertNotEquals(WorkspaceProvisioningStatus.READY,f.workspace.getProvisioningStatus());
        }
    }
    @Test void uncontendedInitializationStillCompletes() throws Exception {
        try (var f = new Fixture()) {
            assertNotNull(f.service.syncFromShared("alice","main"));
            assertEquals(MERGED,f.destination.getDslAtHead("main"));
            assertEquals(MERGED,f.destination.getDslAtHead("sync-base"));
        }
    }
    @Test void unchangedTrackingBaseDoesNotCreateAnExtraCommit() throws Exception {
        try (var f = new Fixture()) {
            f.initialized(); String old = f.destination.getHeadCommit("sync-base");
            when(f.merger.mergeContent(anyString(),anyString(),anyString())).thenReturn(new TaxDslMergeResult(BASE,List.of()));
            assertNotNull(f.service.syncFromShared("alice","main"));
            assertEquals(old,f.destination.getHeadCommit("sync-base"));
        }
    }
    @Test void uncontendedProvisioningStillCompletes() throws Exception {
        try (var f = new Fixture()) {
            var manager = new WorkspaceManager(f.rows,50,f.catalog,f.repositories);
            assertEquals(WorkspaceProvisioningStatus.READY,manager.provisionWorkspaceRepository("alice","workspace").getProvisioningStatus());
            assertEquals(BASE,f.destination.getDslAtHead("main"));
        }
    }
}
