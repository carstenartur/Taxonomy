package com.taxonomy.workspace.service;

import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitNativeSyncPortfolioProjectionTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void availablePortfolioParticipatesInPullAndPublishWithoutOverridingAvailability(boolean pull)
            throws Exception {
        try (var repository = new DslGitRepository()) {
            repository.commitDsl("draft", document("Original", "Original"), "alice", "Initial version");
            repository.createBranch("review", "draft");
            String sharedHead = repository.commitDsl("draft", document("Shared title", "Original"),
                    "bob", "Shared changes");

            var context = new WorkspaceContext("alice", null, "review", "repository-a");
            var resolver = mock(WorkspaceContextResolver.class);
            when(resolver.resolveRepositoryContextForUser("alice")).thenReturn(
                    RepositoryContext.centralWrite("repository-a", "review", "alice"));
            var repositories = mock(DslGitRepositoryFactory.class);
            when(repositories.getSystemRepository()).thenReturn(repository);
            when(repositories.getCentralRepository("repository-a")).thenReturn(repository);
            var system = mock(SystemRepositoryService.class);
            when(system.getSharedBranch()).thenReturn("draft");
            var state = new SyncState();
            state.setUsername("alice");
            state.setSyncStatus("DIVERGED");
            state.setUnpublishedCommitCount(1);
            var states = mock(SyncStateRepository.class);
            when(states.findByUsername("alice")).thenReturn(Optional.of(state));
            when(states.save(any(SyncState.class))).thenAnswer(call -> call.getArgument(0));
            var portfolio = new InMemoryPortfolio(repository, document("Original", "Local requirement"));
            var versions = new WorkspaceArchitectureVersionPort() {
                @Override
                public <T> T version(RepositoryContext selected, String rationale, GitAction<T> action)
                        throws IOException {
                    return action.run();
                }
            };
            var service = new GitNativeSyncIntegrationService(states, mock(UserWorkspaceRepository.class),
                    system, repositories, new SemanticGitMergeService(), portfolio, resolver, versions);

            String result = pull ? service.syncFromShared("alice", "review")
                    : service.publishToShared("alice", "review");

            String destination = pull ? "review" : "draft";
            String mergedDsl = repository.getDslAtHead(destination);
            assertThat(result).isEqualTo(repository.getHeadCommit(destination));
            assertThat(portfolio.committedContexts).containsExactly(context);
            assertThat(portfolio.materialized).singleElement().satisfies(projection -> {
                assertThat(projection.branch()).isEqualTo(destination);
                assertThat(projection.username()).isEqualTo("alice");
                assertThat(projection.context()).isEqualTo(context);
                assertThat(projection.dsl()).isEqualTo(mergedDsl)
                        .contains("Shared title", "Local requirement");
            });
            assertThat(state.getSyncStatus()).isEqualTo("UP_TO_DATE");
            if (pull) {
                assertThat(state.getLastSyncedCommitId()).isEqualTo(sharedHead);
                assertThat(state.getLastPublishedCommitId()).isNull();
            } else {
                assertThat(state.getLastPublishedCommitId()).isEqualTo(result);
                assertThat(state.getLastSyncedCommitId()).isEqualTo(result);
                assertThat(state.getUnpublishedCommitCount()).isZero();
            }
        }
    }

    /** A present portfolio persists its accepted content and records the rebuilt projection. */
    private static final class InMemoryPortfolio implements WorkspacePortfolioGitPort {
        private final DslGitRepository repository;
        private final String acceptedDsl;
        private final List<WorkspaceContext> committedContexts = new ArrayList<>();
        private final List<Projection> materialized = new ArrayList<>();

        private InMemoryPortfolio(DslGitRepository repository, String acceptedDsl) {
            this.repository = repository;
            this.acceptedDsl = acceptedDsl;
        }

        @Override
        public String commitPortfolio(String branch, String message, String username, WorkspaceContext context)
                throws IOException {
            committedContexts.add(context);
            return repository.commitDsl(branch, acceptedDsl, username, message);
        }

        @Override
        public void materializePortfolio(String dsl, String username, WorkspaceContext context) {
            materialized.add(new Projection(context.currentBranch(), username, context, dsl));
        }

        @Override
        public void materializePortfolioHead(String branch, String username, WorkspaceContext context)
                throws IOException {
            materialized.add(new Projection(branch, username, context, repository.getDslAtHead(branch)));
        }
    }

    private record Projection(String branch, String username, WorkspaceContext context, String dsl) { }

    private static String document(String title, String text) {
        return """
                meta {
                  language: "taxdsl";
                  version: "2.1";
                  namespace: "test";
                }
                projectRequirement P-001 REQ-001 {
                  title: "%s";
                  text: "%s";
                }
                """.formatted(title, text);
    }
}
