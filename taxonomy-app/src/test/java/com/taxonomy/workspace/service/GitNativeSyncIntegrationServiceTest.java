package com.taxonomy.workspace.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitNativeSyncIntegrationServiceTest {

    private final SyncStateRepository syncStateRepository = mock(SyncStateRepository.class);
    private final UserWorkspaceRepository workspaceRepository = mock(UserWorkspaceRepository.class);
    private final SystemRepositoryService systemRepositoryService = mock(SystemRepositoryService.class);
    private final DslGitRepositoryFactory repositoryFactory = mock(DslGitRepositoryFactory.class);
    private final SemanticGitMergeService semanticMergeService = mock(SemanticGitMergeService.class);
    private final PortfolioGitService portfolioGitService = mock(PortfolioGitService.class);
    private final DslGitRepository systemRepository = mock(DslGitRepository.class);
    private final DslGitRepository isolatedWorkspaceRepository = mock(DslGitRepository.class);

    private GitNativeSyncIntegrationService service;
    private SyncState state;

    @BeforeEach
    void setUp() {
        service = new GitNativeSyncIntegrationService(
                syncStateRepository,
                workspaceRepository,
                systemRepositoryService,
                repositoryFactory,
                semanticMergeService,
                portfolioGitService);

        state = new SyncState();
        state.setUsername("alice");
        state.setWorkspaceId("workspace-a");
        state.setSyncStatus("UP_TO_DATE");

        when(syncStateRepository.findByUsername("alice")).thenReturn(Optional.of(state));
        when(syncStateRepository.save(any(SyncState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(systemRepositoryService.getSharedBranch()).thenReturn("draft");
        when(repositoryFactory.getSystemRepository()).thenReturn(systemRepository);
        when(repositoryFactory.getWorkspaceRepository("workspace-a"))
                .thenReturn(isolatedWorkspaceRepository);
    }

    @Test
    void normalPullEndpointUsesSystemAndWorkspaceRepositoriesInsteadOfStaleLocalSharedBranch()
            throws Exception {
        String base = "requirement P-1.REQ-0 {\n  text: \"base\";\n}\n";
        String ours = base + "requirement P-1.REQ-A {\n  text: \"Alice\";\n}\n";
        String theirs = base + "requirement P-1.REQ-B {\n  text: \"Bob\";\n}\n";
        String merged = base
                + "requirement P-1.REQ-A {\n  text: \"Alice\";\n}\n"
                + "requirement P-1.REQ-B {\n  text: \"Bob\";\n}\n";

        when(isolatedWorkspaceRepository.getDslAtHead("sync-base")).thenReturn(base);
        when(isolatedWorkspaceRepository.getDslAtHead("feature/alice")).thenReturn(ours);
        when(systemRepository.getDslAtHead("draft")).thenReturn(theirs);
        when(semanticMergeService.mergeContent(base, ours, theirs))
                .thenReturn(new TaxDslMergeResult(merged, List.of()));
        when(isolatedWorkspaceRepository.commitDsl(
                eq("feature/alice"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("local-merge-commit");
        when(isolatedWorkspaceRepository.commitDsl(
                eq("sync-base"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("tracking-commit");
        when(systemRepository.getHeadCommit("draft")).thenReturn("shared-head");

        String commit = service.syncFromShared("alice", "feature/alice");

        assertThat(commit).isEqualTo("local-merge-commit");
        verify(systemRepository).getDslAtHead("draft");
        verify(isolatedWorkspaceRepository).getDslAtHead("feature/alice");
        verify(semanticMergeService).mergeContent(base, ours, theirs);
        verify(semanticMergeService, never()).mergeBranches(any(), any(), any(), any());
        verify(portfolioGitService).materialize(
                eq(merged), eq("alice"),
                eq(new WorkspaceContext("alice", "workspace-a", "feature/alice")));
        assertThat(state.getLastSyncedCommitId()).isEqualTo("shared-head");
    }

    @Test
    void normalPublishEndpointMergesSharedChangesBackIntoWorkspaceAfterPush()
            throws Exception {
        String base = "requirement P-1.REQ-0 {\n  text: \"base\";\n}\n";
        String shared = base + "requirement P-1.REQ-B {\n  text: \"Bob\";\n}\n";
        String workspace = base + "requirement P-1.REQ-A {\n  text: \"Alice\";\n}\n";
        String merged = base
                + "requirement P-1.REQ-A {\n  text: \"Alice\";\n}\n"
                + "requirement P-1.REQ-B {\n  text: \"Bob\";\n}\n";

        when(isolatedWorkspaceRepository.getDslAtHead("sync-base")).thenReturn(base);
        when(systemRepository.getDslAtHead("draft")).thenReturn(shared);
        when(isolatedWorkspaceRepository.getDslAtHead("feature/alice")).thenReturn(workspace);
        when(semanticMergeService.mergeContent(base, shared, workspace))
                .thenReturn(new TaxDslMergeResult(merged, List.of()));
        when(systemRepository.commitDsl(
                eq("draft"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("shared-merge-commit");
        when(isolatedWorkspaceRepository.commitDsl(
                eq("feature/alice"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("workspace-sync-commit");
        when(isolatedWorkspaceRepository.commitDsl(
                eq("sync-base"), eq(merged), eq("alice"), any(String.class)))
                .thenReturn("tracking-commit");

        String commit = service.publishToShared("alice", "feature/alice");

        assertThat(commit).isEqualTo("shared-merge-commit");
        verify(systemRepository).commitDsl(
                eq("draft"), eq(merged), eq("alice"), any(String.class));
        verify(isolatedWorkspaceRepository).commitDsl(
                eq("feature/alice"), eq(merged), eq("alice"), any(String.class));
        verify(portfolioGitService).materialize(
                eq(merged), eq("shared"), eq(WorkspaceContext.SHARED));
        verify(portfolioGitService).materialize(
                eq(merged), eq("alice"),
                eq(new WorkspaceContext("alice", "workspace-a", "feature/alice")));
        verify(semanticMergeService, never()).mergeBranches(any(), any(), any(), any());
        assertThat(state.getLastPublishedCommitId()).isEqualTo("shared-merge-commit");
        assertThat(state.getLastSyncedCommitId()).isEqualTo("shared-merge-commit");
    }
}
