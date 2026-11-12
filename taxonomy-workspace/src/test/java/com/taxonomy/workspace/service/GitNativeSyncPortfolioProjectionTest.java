package com.taxonomy.workspace.service;

import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceBranchSyncState;
import com.taxonomy.workspace.model.WorkspaceSyncScope;
import com.taxonomy.workspace.repository.WorkspaceSyncStateUpdatesImpl;
import org.hibernate.cfg.Configuration;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitNativeSyncPortfolioProjectionTest {
    @ParameterizedTest
    @CsvSource({"true, false", "false, false", "true, true", "false, true"})
    void availablePortfolioParticipatesInPullAndPublishWithoutOverridingAvailability(
            boolean pull, boolean legacyValidation)
            throws Exception {
        var database = new Configuration().addAnnotatedClass(SyncState.class).addAnnotatedClass(UserWorkspace.class)
                .addAnnotatedClass(WorkspaceBranchSyncState.class).setProperty("hibernate.connection.username","SA")
                .setProperty("hibernate.connection.password","").setProperty("hibernate.hbm2ddl.auto","create-drop")
                .setProperty("hibernate.search.enabled","false")
                .setProperty("hibernate.connection.url","jdbc:hsqldb:mem:portfolio-progress-" + UUID.randomUUID());
        try (var repository = new DslGitRepository(); var factory=database.buildSessionFactory()) {
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
            var system = NativeSyncTestCatalog.create();
            when(system.getSharedBranch()).thenReturn("draft");
            var sourceMetadata = new com.taxonomy.workspace.model.SystemRepository();
            sourceMetadata.setRepositoryId("repository-a");
            when(system.getRepository("repository-a")).thenReturn(sourceMetadata);
            var entities=SharedEntityManagerCreator.createSharedEntityManager(factory);
            var scoped=new WorkspaceSyncStateUpdatesImpl(entities,new JpaTransactionManager(factory));
            var states=new JpaRepositoryFactory(entities).getRepository(SyncStateRepository.class,RepositoryFragments.just(scoped));
            var scope=new WorkspaceSyncScope("alice","repository-a",null,"review");
            states.recordLocalChanges(scope,1,1,Instant.EPOCH);
            assertThat(states.readScope(scope).getSyncStatus()).isEqualTo("DIVERGED");
            InMemoryPortfolio portfolio = legacyValidation
                    ? new LegacyPortfolio(repository, document("Original", "Local requirement"))
                    : new ContextAwarePortfolio(repository, document("Original", "Local requirement"));
            var versions = new WorkspaceArchitectureVersionPort() {
                @Override
                public <T> T version(RepositoryContext selected, String rationale, GitAction<T> action)
                        throws IOException {
                    return action.run();
                }
            };
            var service = new GitNativeSyncIntegrationService(states, mock(UserWorkspaceRepository.class),
                    system, repositories, new SemanticGitMergeService(), portfolio, resolver, versions,
                mock(RepositoryMembershipService.class, invocation -> true));

            String result = pull ? service.syncFromShared("alice", "review")
                    : service.publishToShared("alice", "review");

            String destination = pull ? "review" : "draft";
            var target = new WorkspaceContext("alice", null, destination, "repository-a");
            String mergedDsl = repository.getDslAtHead(destination);
            assertThat(result).isEqualTo(repository.getHeadCommit(destination));
            assertThat(portfolio.committedContexts).containsExactly(context);
            assertThat(portfolio.materialized).singleElement().satisfies(projection -> {
                assertThat(projection.branch()).isEqualTo(destination);
                assertThat(projection.username()).isEqualTo("alice");
                assertThat(projection.context()).isEqualTo(target);
                assertThat(projection.dsl()).isEqualTo(mergedDsl)
                        .contains("Shared title", "Local requirement");
            });
            assertThat(portfolio.validated).singleElement().satisfies(validation -> {
                assertThat(validation.context()).isEqualTo(legacyValidation ? null : target);
                assertThat(validation.localDsl()).isNull();
                assertThat(validation.candidateDsl()).isEqualTo(mergedDsl);
            });
            var state=states.readScope(scope);
            assertThat(state.getSyncStatus()).isEqualTo("UP_TO_DATE");
            assertThat(states.readScope(new WorkspaceSyncScope("alice","repository-a",null,"unselected"))
                    .getLastSyncedCommitId()).isNull();
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
    private abstract static class InMemoryPortfolio implements WorkspacePortfolioGitPort {
        private final DslGitRepository repository;
        private final String acceptedDsl;
        private final List<WorkspaceContext> committedContexts = new ArrayList<>();
        private final List<Projection> materialized = new ArrayList<>();
        final List<Validation> validated = new ArrayList<>();

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

    private static final class ContextAwarePortfolio extends InMemoryPortfolio {
        private ContextAwarePortfolio(DslGitRepository repository, String acceptedDsl) {
            super(repository, acceptedDsl);
        }

        @Override
        public void validatePortfolio(String localDsl, String candidateDsl, String username, WorkspaceContext context) {
            validated.add(new Validation(localDsl, candidateDsl, context));
        }
    }

    /** Older adapters must still validate the candidate before pull or publication. */
    private static final class LegacyPortfolio extends InMemoryPortfolio {
        private LegacyPortfolio(DslGitRepository repository, String acceptedDsl) {
            super(repository, acceptedDsl);
        }

        @Override
        public void validatePortfolio(String localDsl, String candidateDsl) {
            validated.add(new Validation(localDsl, candidateDsl, null));
        }
    }

    private record Validation(String localDsl, String candidateDsl, WorkspaceContext context) { }

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
