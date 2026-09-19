package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspacePortfolioDocumentPort;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import org.springframework.stereotype.Service;
import java.io.IOException;

/** Captures a repository once and preserves the editor checkpoint/import publication boundary. */
@Service
public class WorkspacePortfolioDocumentAdapter implements WorkspacePortfolioDocumentPort {
    private final DslGitRepositoryFactory repositories;
    private final SemanticGitMergeService merges;
    private final WorkspaceArchitectureVersionPort versions;

    public WorkspacePortfolioDocumentAdapter(DslGitRepositoryFactory repositories,
            SemanticGitMergeService merges, WorkspaceArchitectureVersionPort versions) {
        this.repositories = repositories;
        this.merges = merges;
        this.versions = versions;
    }

    @Override
    public DocumentHandle resolveRepository(WorkspaceContext context) throws IOException {
        DslGitRepository repository = repositories.resolveRepository(context);
        return new DocumentHandle() {
            @Override
            public String getDslAtHead(String branch) throws IOException {
                return repository.getDslAtHead(branch);
            }

            @Override
            public String getHeadCommit(String branch) throws IOException {
                return repository.getHeadCommit(branch);
            }

            @Override
            public String getDslAtCommit(String commitId) throws IOException {
                return repository.getDslAtCommit(commitId);
            }

            @Override
            public String commitDsl(String branch, String dsl, String author, String message) throws IOException {
                return publish(branch, author, message, () -> repository.commitDsl(branch, dsl, author, message));
            }

            @Override
            public String commitDslIfHeadMatches(String branch, String expectedHead, String dsl,
                    String author, String message) throws IOException {
                return publish(branch, author, message,
                        () -> repository.commitDslIfHeadMatches(branch, expectedHead, dsl, author, message));
            }

            @Override
            public String verifyHeadForVersion(String branch, String expectedHead,
                    String author, String message) throws IOException {
                return publish(branch, author, message, () -> {
                    String actualHead = repository.getHeadCommit(branch);
                    if (!java.util.Objects.equals(expectedHead, actualHead)) {
                        throw new com.taxonomy.workspace.service.BranchHeadConflictException(
                                branch, expectedHead, actualHead, "Portfolio branch changed before publication");
                    }
                    return expectedHead;
                });
            }

            @Override
            public MergeResult mergeBranches(String fromBranch, String intoBranch, String author, String message)
                    throws IOException {
                return publish(intoBranch, author, message, () -> {
                    var outcome = merges.mergeBranches(repository, fromBranch, intoBranch, author, message);
                    return new MergeResult(outcome.success(), outcome.commitId(), outcome.semanticFallback(), outcome.conflicts());
                });
            }

            private <T> T publish(String branch, String author, String message,
                    WorkspaceArchitectureVersionPort.GitAction<T> action) throws IOException {
                WorkspaceContext selected = context == null ? new WorkspaceContext(author, null, branch) : context;
                RepositoryContext target = selected.workspaceId() == null || selected.workspaceId().isBlank()
                        ? RepositoryContext.centralWrite(selected.repositoryId(), branch, author)
                        : RepositoryContext.workspace(selected.repositoryId(), selected.workspaceId(), branch, author);
                // Acquire the journal/version boundary before the Repository monitor. ORM preparation
                // and materialization stay outside the monitor; editor pending operations checkpoint first.
                return versions.version(target, message, () -> {
                    synchronized (repository.getGitRepository()) {
                        return action.run();
                    }
                });
            }
        };
    }
}
