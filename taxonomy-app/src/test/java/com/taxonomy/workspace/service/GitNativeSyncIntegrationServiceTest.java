package com.taxonomy.workspace.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitNativeSyncIntegrationServiceTest {

    private final SyncStateRepository syncStateRepository = mock(SyncStateRepository.class);
    private final UserWorkspaceRepository workspaceRepository = mock(UserWorkspaceRepository.class);
    private final SystemRepositoryService systemRepositoryService = mock(SystemRepositoryService.class);
    private final DslGitRepositoryFactory repositoryFactory = mock(DslGitRepositoryFactory.class);
    private final SemanticGitMergeService semanticMergeService = mock(SemanticGitMergeService.class);
    private final WorkspacePortfolioGitPort portfolioGitPort = mock(WorkspacePortfolioGitPort.class);
    private final WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
    private final DslGitRepository primaryRepository = mock(DslGitRepository.class);
    private final DslGitRepository sourceRepository = mock(DslGitRepository.class);
    private final DslGitRepository isolatedWorkspaceRepository = mock(DslGitRepository.class);

    private GitNativeSyncIntegrationService service;
    private SyncState state;
    private UserWorkspace workspace;
    private SystemRepository sourceMetadata;

    @BeforeEach
    void setUp() {
        when(repositoryFactory.getSystemRepository()).thenReturn(primaryRepository);

        service = new GitNativeSyncIntegrationService(
                syncStateRepository,
                workspaceRepository,
                systemRepositoryService,
                repositoryFactory,
                semanticMergeService,
                portfolioGitPort,
                contextResolver);

        state = new SyncState();
        state.setUsername("alice");
        state.setWorkspaceId("workspace-a");
        state.setSyncStatus("UP_TO_DATE");

        workspace = new UserWorkspace();
        workspace.setWorkspaceId("workspace-a");
        workspace.setUsername("alice");
        workspace.setSourceRepositoryId("repo-a");
        workspace.setSourceBranch("draft");
        workspace.setSyncTargetBranch("draft");
        workspace.setCurrentBranch("feature/alice");

        sourceMetadata = new SystemRepository();
        sourceMetadata.setRepositoryId("repo-a");
        sourceMetadata.setSlug("repo-a");
        sourceMetadata.setDefaultBranch("draft");
        sourceMetadata.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        sourceMetadata.setLifecycleState(RepositoryLifecycleState.ACTIVE);
        sourceMetadata.setPrimaryRepo(true);

        when(syncStateRepository.findByUsername("alice")).thenReturn(Optional.of(state));
        when(syncStateRepository.save(any(SyncState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(workspaceRepository.findByWorkspaceId("workspace-a"))
                .thenReturn(Optional.of(workspace));
        when(workspaceRepository.save(any(UserWorkspace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(contextResolver.resolveRepositoryContextForUser("alice"))
                .thenReturn(RepositoryContext.workspace(
                        "repo-a", "workspace-a", "feature/alice", "alice"));
        when(systemRepositoryService.getRepository("repo-a")).thenReturn(sourceMetadata);
        when(repositoryFactory.getCentralRepository("repo-a")).thenReturn(sourceRepository);
        when(repositoryFactory.openWorkspaceRepository("workspace-a"))
                .thenReturn(isolatedWorkspaceRepository);
    }

    @Test
    void normalPullEndpointUsesRecordedSourceAndWorkspaceRepositories()
            throws Exception {
        String base = "requirement P-1.REQ-0 {\n  text: \"base\";\n}\n";
        String ours = base + "requirement P-1.REQ-A {\n  text: \"Alice\";\n}\n";
        String theirs = base + "requirement P-1.REQ-B {\n  text: \"Bob\";\n}\n";
        String merged = base
                + "requirement P-1.REQ-A {\n  text: \"Alice\";\n}\n"
                + "requirement P-1.REQ-B {\n  text: \"Bob\";\n}\n";

        when(isolatedWorkspaceRepository.getDslAtHead("sync-base")).thenReturn(base);
        when(isolatedWorkspaceRepository.getDslAtHead("feature/alice")).thenReturn(ours);
        when(sourceRepository.getDslAtHead("draft")).thenReturn(theirs);
        when(semanticMergeService.mergeContent(base, ours, theirs))
                .thenReturn(new TaxDslMergeResult(merged, List.of()));
        when(isolatedWorkspaceRepository.commitDsl(
                eq("feature/alice"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("local-merge-commit");
        when(isolatedWorkspaceRepository.commitDsl(
                eq("sync-base"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("tracking-commit");
        when(sourceRepository.getHeadCommit("draft")).thenReturn("source-head");

        String commit = service.syncFromShared("alice", "feature/alice");

        WorkspaceContext exactContext = new WorkspaceContext(
                "alice", "workspace-a", "feature/alice", "repo-a");
        assertThat(commit).isEqualTo("local-merge-commit");
        verify(repositoryFactory).getCentralRepository("repo-a");
        verify(sourceRepository).getDslAtHead("draft");
        verify(primaryRepository, never()).getDslAtHead(any());
        verify(isolatedWorkspaceRepository, times(2)).getDslAtHead("feature/alice");
        verify(semanticMergeService).mergeContent(base, ours, theirs);
        verify(semanticMergeService, never()).mergeBranches(any(), any(), any(), any());
        verify(portfolioGitPort).commitPortfolio(
                eq("feature/alice"), any(String.class), eq("alice"), eq(exactContext));
        verify(portfolioGitPort).materializePortfolio(merged, "alice", exactContext);
        assertThat(state.getLastSyncedCommitId()).isEqualTo("source-head");
        assertThat(workspace.getLastFetchedCommit()).isEqualTo("source-head");
        assertThat(workspace.getLastIntegratedCommit()).isEqualTo("source-head");
    }

    @Test
    void staleDraftFromVolatileUiStateIsReplacedByPersistentMainBranch()
            throws Exception {
        workspace.setCurrentBranch("main");
        when(contextResolver.resolveRepositoryContextForUser("alice"))
                .thenReturn(RepositoryContext.workspace(
                        "repo-a", "workspace-a", "main", "alice"));
        String base = "requirement REQ-BASE {\n  text: \"base\";\n}\n";
        String ours = base + "requirement REQ-LOCAL {\n  text: \"local\";\n}\n";
        String theirs = base + "requirement REQ-SHARED {\n  text: \"shared\";\n}\n";
        String merged = ours + "requirement REQ-SHARED {\n  text: \"shared\";\n}\n";

        when(isolatedWorkspaceRepository.getDslAtHead("sync-base")).thenReturn(base);
        when(isolatedWorkspaceRepository.getDslAtHead("main")).thenReturn(ours);
        when(sourceRepository.getDslAtHead("draft")).thenReturn(theirs);
        when(semanticMergeService.mergeContent(base, ours, theirs))
                .thenReturn(new TaxDslMergeResult(merged, List.of()));
        when(isolatedWorkspaceRepository.commitDsl(
                eq("main"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("main-merge-commit");
        when(isolatedWorkspaceRepository.commitDsl(
                eq("sync-base"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("tracking-commit");
        when(sourceRepository.getHeadCommit("draft")).thenReturn("source-head");

        String commit = service.syncFromShared("alice", "draft");

        assertThat(commit).isEqualTo("main-merge-commit");
        verify(isolatedWorkspaceRepository, times(2)).getDslAtHead("main");
        verify(isolatedWorkspaceRepository, never()).getDslAtHead("draft");
        verify(portfolioGitPort).commitPortfolio(
                eq("main"), any(String.class), eq("alice"),
                eq(new WorkspaceContext(
                        "alice", "workspace-a", "main", "repo-a")));
    }

    @Test
    void normalPublishEndpointWritesAndMaterializesTheRecordedSourceRepository()
            throws Exception {
        String base = "requirement P-1.REQ-0 {\n  text: \"base\";\n}\n";
        String shared = base + "requirement P-1.REQ-B {\n  text: \"Bob\";\n}\n";
        String local = base + "requirement P-1.REQ-A {\n  text: \"Alice\";\n}\n";
        String merged = base
                + "requirement P-1.REQ-A {\n  text: \"Alice\";\n}\n"
                + "requirement P-1.REQ-B {\n  text: \"Bob\";\n}\n";

        when(isolatedWorkspaceRepository.getDslAtHead("sync-base")).thenReturn(base);
        when(sourceRepository.getDslAtHead("draft")).thenReturn(shared);
        when(isolatedWorkspaceRepository.getDslAtHead("feature/alice")).thenReturn(local);
        when(semanticMergeService.mergeContent(base, shared, local))
                .thenReturn(new TaxDslMergeResult(merged, List.of()));
        when(sourceRepository.commitDsl(
                eq("draft"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("source-merge-commit");
        when(isolatedWorkspaceRepository.commitDsl(
                eq("feature/alice"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("workspace-sync-commit");
        when(isolatedWorkspaceRepository.commitDsl(
                eq("sync-base"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("tracking-commit");

        String commit = service.publishToShared("alice", "feature/alice");

        assertThat(commit).isEqualTo("source-merge-commit");
        verify(sourceRepository).commitDsl(
                eq("draft"), eq(merged), eq("alice"), any(String.class));
        verify(primaryRepository, never()).commitDsl(any(), any(), any(), any());
        verify(portfolioGitPort).materializePortfolio(
                eq(merged), eq("shared"),
                eq(new WorkspaceContext("shared", null, "draft", "repo-a")));
        verify(portfolioGitPort).materializePortfolio(
                eq(merged), eq("alice"),
                eq(new WorkspaceContext(
                        "alice", "workspace-a", "feature/alice", "repo-a")));
        assertThat(state.getLastPublishedCommitId()).isEqualTo("source-merge-commit");
        assertThat(workspace.getCurrentCommit()).isEqualTo("workspace-sync-commit");
    }

    @Test
    void nonPrimaryRepositoryProjectsIntoItsOwnCentralScope()
            throws Exception {
        sourceMetadata.setPrimaryRepo(false);
        sourceMetadata.setDefaultBranch("main");
        workspace.setSourceBranch("main");
        workspace.setSyncTargetBranch("main");
        String base = "requirement R0 { text: \"base\"; }\n";
        String local = base + "requirement R1 { text: \"local\"; }\n";

        when(isolatedWorkspaceRepository.getDslAtHead("sync-base")).thenReturn(base);
        when(sourceRepository.getDslAtHead("main")).thenReturn(base);
        when(isolatedWorkspaceRepository.getDslAtHead("feature/alice")).thenReturn(local);
        when(semanticMergeService.mergeContent(base, base, local))
                .thenReturn(new TaxDslMergeResult(local, List.of()));
        when(sourceRepository.commitDsl(eq("main"), eq(local), eq("alice"), any(String.class)))
                .thenReturn("repo-a-head");
        when(isolatedWorkspaceRepository.getHeadCommit("feature/alice"))
                .thenReturn("workspace-head");
        when(isolatedWorkspaceRepository.commitDsl(
                eq("sync-base"), eq(local), eq("alice"), any(String.class)))
                .thenReturn("tracking-commit");

        service.publishToShared("alice", "feature/alice");

        verify(portfolioGitPort).materializePortfolio(
                local,
                "shared",
                new WorkspaceContext("shared", null, "main", "repo-a"));
        verify(portfolioGitPort, never()).materializePortfolio(
                any(), eq("shared"), eq(WorkspaceContext.SHARED));
    }

    @Test
    void explicitlyAddressedWorkspaceCarriesPersistedRepositoryIdentity()
            throws Exception {
        String base = "requirement R0 { text: \"base\"; }\n";
        String local = base + "requirement R1 { text: \"local\"; }\n";
        String shared = base + "requirement R2 { text: \"shared\"; }\n";
        String merged = local + "requirement R2 { text: \"shared\"; }\n";

        when(isolatedWorkspaceRepository.getDslAtHead("sync-base")).thenReturn(base);
        when(isolatedWorkspaceRepository.getDslAtHead("main")).thenReturn(local);
        when(sourceRepository.getDslAtHead("draft")).thenReturn(shared);
        when(semanticMergeService.mergeContent(base, local, shared))
                .thenReturn(new TaxDslMergeResult(merged, List.of()));
        when(isolatedWorkspaceRepository.commitDsl(
                eq("main"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("local-head");
        when(isolatedWorkspaceRepository.commitDsl(
                eq("sync-base"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("tracking-head");
        when(sourceRepository.getHeadCommit("draft")).thenReturn("source-head");

        service.syncFromSharedToWorkspace("alice", "workspace-a");

        verify(portfolioGitPort).commitPortfolio(
                eq("main"), any(String.class), eq("alice"),
                eq(new WorkspaceContext(
                        "alice", "workspace-a", "main", "repo-a")));
        verify(contextResolver, never()).resolveForUser(any());
    }

    @Test
    void contextRepositoryMismatchFailsBeforeGitOrPortfolioMutation() {
        when(contextResolver.resolveRepositoryContextForUser("alice"))
                .thenReturn(RepositoryContext.workspace(
                        "repo-b", "workspace-a", "feature/alice", "alice"));

        assertThatThrownBy(() -> service.syncFromShared("alice", "feature/alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match persisted source provenance");

        verify(repositoryFactory, never()).getCentralRepository(any());
        verify(portfolioGitPort, never()).commitPortfolio(
                any(), any(), any(), any());
    }

    @Test
    void centralSynchronizationUsesExactRepositoryInsteadOfLegacyPrimarySentinel()
            throws Exception {
        when(contextResolver.resolveRepositoryContextForUser("alice"))
                .thenReturn(RepositoryContext.centralRead(
                        "repo-a", "draft", "alice"));
        when(systemRepositoryService.getSharedBranch()).thenReturn("draft");
        when(semanticMergeService.mergeBranches(
                sourceRepository, "draft", "draft", "alice"))
                .thenReturn(new SemanticGitMergeService.MergeOutcome(
                        true, "central-head", false, List.of(), null));
        when(sourceRepository.getHeadCommit("draft")).thenReturn("central-head");

        String commit = service.syncFromShared("alice", null);

        WorkspaceContext context = new WorkspaceContext(
                "alice", null, "draft", "repo-a");
        assertThat(commit).isEqualTo("central-head");
        verify(repositoryFactory).getCentralRepository("repo-a");
        verify(repositoryFactory, never()).getSystemRepository();
        verify(portfolioGitPort).commitPortfolio(
                eq("draft"), any(String.class), eq("alice"), eq(context));
        verify(portfolioGitPort).materializePortfolioHead(
                "draft", "alice", context);
    }

    @Test
    void workspaceWithoutSourceRepositoryFailsClosedInsteadOfUsingPrimaryRepository()
            throws Exception {
        workspace.setSourceRepositoryId(null);

        assertThatThrownBy(() -> service.syncFromShared("alice", "feature/alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sourceRepositoryId");

        verify(repositoryFactory, never()).getCentralRepository(any());
        verify(primaryRepository, never()).getDslAtHead(any());
    }
}
