package com.taxonomy.versioning.service;

import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryStateGuard;
import com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticDslPortfolioProjectionTest {
    @Test
    void availablePortfolioMaterializesTheMergedHeadInTheSelectedWorkspace() throws Exception {
        try (var repository = new DslGitRepository()) {
            String original = "projectRequirement P-001 REQ-001 { text: \"Original\"; }\n";
            String reviewed = "projectRequirement P-001 REQ-001 { text: \"Reviewed\"; }\n";
            repository.commitDsl("draft", original, "alice", "Initial version");
            repository.createBranch("review", "draft");
            String reviewedCommit = repository.commitDsl("review", reviewed, "alice", "Reviewed requirements");

            var context = new WorkspaceContext("alice", "workspace-a", "draft", "repository-a");
            var resolver = mock(WorkspaceResolver.class);
            when(resolver.resolveCurrentUsername()).thenReturn("alice");
            when(resolver.resolveCurrentContext()).thenReturn(context);
            when(resolver.resolveCurrentRepositoryContext()).thenReturn(
                    RepositoryContext.workspace("repository-a", "workspace-a", "draft", "alice"));
            var repositories = mock(DslGitRepositoryFactory.class);
            when(repositories.resolveRepository(context)).thenReturn(repository);

            var projections = new ArrayList<Projection>();
            // A present implementation only supplies its projection behavior. The
            // consumer must admit it through the port's default availability check.
            VersioningPortfolioGitPort portfolio = (branch, username, selected) -> projections.add(
                    new Projection(branch, username, selected, repository.getDslAtHead(branch)));
            var checkpointed = new ArrayList<RepositoryContext>();
            var versions = new WorkspaceArchitectureVersionPort() {
                @Override
                public <T> T version(RepositoryContext selected, String rationale, GitAction<T> action)
                        throws IOException {
                    checkpointed.add(selected);
                    return action.run();
                }
            };
            var facade = new SemanticDslOperationsFacade(repositories, mock(CommitIndexService.class),
                    mock(ConflictDetectionService.class), mock(RepositoryStateGuard.class),
                    mock(RepositoryStateService.class), resolver, new SemanticGitMergeService(),
                    portfolio, versions);

            String result = facade.merge("review", "draft");

            assertThat(result).isEqualTo(reviewedCommit);
            assertThat(repository.getHeadCommit("draft")).isEqualTo(result);
            assertThat(projections).containsExactly(new Projection("draft", "alice", context, reviewed));
            assertThat(checkpointed).containsExactly(
                    RepositoryContext.workspace("repository-a", "workspace-a", "draft", "alice"),
                    RepositoryContext.workspace("repository-a", "workspace-a", "review", "alice"));
        }
    }

    private record Projection(String branch, String username, WorkspaceContext context, String dsl) { }
}
