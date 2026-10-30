package com.taxonomy.workspace.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslCommit;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Real source/workspace histories exercise the complete semantic publication path. */
class GitNativeSyncAncestryTest {
    @Test void publicationPreservesOriginalContributionsAndAdoptsTheSameMergeWithoutAnEchoCommit() throws Exception {
        try (var fixture = new Fixture()) {
            String central = fixture.source.getHeadCommit("main");
            String first = fixture.local.getHeadCommit("main");
            String second = fixture.local.commitDsl("main", "# second request", "bob", "Second request version");
            byte[] original = fixture.local.getGitRepository().open(ObjectId.fromString(first)).getBytes();
            when(fixture.merger.mergeContent(anyString(), anyString(), anyString()))
                    .thenReturn(new TaxDslMergeResult("# merged architecture", List.of()));

            String published = fixture.service.publishFromWorkspaceToShared("alice", "workspace");

            assertThat(fixture.source.getHeadCommit("main")).isEqualTo(published);
            assertThat(fixture.local.getHeadCommit("main")).isEqualTo(published);
            try (var walk = new RevWalk(fixture.source.getGitRepository())) {
                assertThat(walk.parseCommit(ObjectId.fromString(published)).getParents())
                        .extracting(ObjectId::name).containsExactly(central, second);
            }
            assertThat(fixture.source.getGitRepository().open(ObjectId.fromString(first)).getBytes()).isEqualTo(original);
            assertThat(fixture.source.isAncestor(first, published)).isTrue();
            assertThat(fixture.source.getDslHistory("main")).extracting(DslCommit::message)
                    .contains("First request version", "Second request version");
            assertThat(fixture.source.getProvenance(published).sources().getFirst().commitId()).isEqualTo(second);
            assertThat(fixture.workspace.getCurrentCommit()).isEqualTo(published);
            assertThat(fixture.workspace.getLastIntegratedCommit()).isEqualTo(published);

            int count = fixture.source.getCommitCount("main");
            assertThat(fixture.service.publishFromWorkspaceToShared("alice", "workspace")).isEqualTo(published);
            assertThat(fixture.source.getCommitCount("main")).isEqualTo(count);
            assertThat(fixture.local.getHeadCommit("main")).isEqualTo(published);
        }
    }

    @Test void aLaterSourceDescendantPullFastForwardsTheWorkspaceAndRetainsItsOriginalVersions() throws Exception {
        try (var fixture = new Fixture()) {
            String original = fixture.local.getHeadCommit("main");
            when(fixture.merger.mergeContent(anyString(), anyString(), anyString()))
                    .thenReturn(new TaxDslMergeResult("# merged architecture", List.of()));
            String published = fixture.service.publishFromWorkspaceToShared("alice", "workspace");
            String later = fixture.source.commitDsl("main", "# later source", "carol", "Later source version");
            when(fixture.merger.mergeContent(anyString(), anyString(), anyString()))
                    .thenReturn(new TaxDslMergeResult("# later source", List.of()));

            assertThat(fixture.service.syncFromSharedToWorkspace("alice", "workspace")).isEqualTo(later);
            assertThat(fixture.local.getHeadCommit("main")).isEqualTo(later);
            assertThat(fixture.local.isAncestor(original, later)).isTrue();
            assertThat(fixture.local.isAncestor(published, later)).isTrue();
            assertThat(fixture.workspace.getLastIntegratedCommit()).isEqualTo(later);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DslGitRepositoryFactory repositories = new DslGitRepositoryFactory(null);
        final DslGitRepository source = repositories.getCentralRepository("architecture");
        final DslGitRepository local = repositories.openWorkspaceRepository("workspace");
        final UserWorkspace workspace = new UserWorkspace();
        final SemanticGitMergeService merger = mock(SemanticGitMergeService.class);
        final GitNativeSyncIntegrationService service;

        Fixture() throws IOException {
            source.commitDsl("main", "# central", "carol", "Central root");
            local.commitDsl("main", "# first request", "alice", "First request version");
            local.commitDsl("sync-base", "# base", "alice", "Accepted semantic base");
            workspace.setWorkspaceId("workspace"); workspace.setUsername("alice");
            workspace.setSourceRepositoryId("architecture"); workspace.setSourceBranch("main"); workspace.setCurrentBranch("main");
            var workspaces = mock(UserWorkspaceRepository.class);
            when(workspaces.findByWorkspaceId("workspace")).thenReturn(Optional.of(workspace));
            when(workspaces.save(any())).thenAnswer(call -> call.getArgument(0));
            var catalog = mock(SystemRepositoryService.class);
            var metadata = new SystemRepository(); metadata.setRepositoryId("architecture"); metadata.setDefaultBranch("main");
            when(catalog.getRepository("architecture")).thenReturn(metadata);
            var states = mock(SyncStateRepository.class);
            when(states.findByUsername("alice")).thenReturn(Optional.of(new SyncState()));
            var versions = new WorkspaceArchitectureVersionPort() {
                @Override public <T> T version(RepositoryContext context, String rationale, GitAction<T> action) throws IOException {
                    return action.run();
                }
            };
            service = new GitNativeSyncIntegrationService(states, workspaces, catalog, repositories, merger,
                    mock(WorkspacePortfolioGitPort.class), mock(WorkspaceContextResolver.class), versions,
                    mock(RepositoryMembershipService.class, invocation -> true));
        }
        @Override public void close() { repositories.close(); }
    }
}
