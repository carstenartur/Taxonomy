package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspacePortfolioDocumentPort;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import org.springframework.stereotype.Service;
import java.io.IOException;

/** Delegates through the same captured repository for reads, checkpoints and semantic merges. */
@Service
public class WorkspacePortfolioDocumentAdapter implements WorkspacePortfolioDocumentPort {
    private final DslGitRepositoryFactory repositories;
    private final SemanticGitMergeService merges;

    public WorkspacePortfolioDocumentAdapter(DslGitRepositoryFactory repositories, SemanticGitMergeService merges) {
        this.repositories = repositories;
        this.merges = merges;
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
            public String commitDsl(String branch, String dsl, String author, String message) throws IOException {
                return repository.commitDsl(branch, dsl, author, message);
            }

            @Override
            public MergeResult mergeBranches(String fromBranch, String intoBranch, String author, String message)
                    throws IOException {
                var outcome = merges.mergeBranches(repository, fromBranch, intoBranch, author, message);
                return new MergeResult(outcome.success(), outcome.commitId(), outcome.semanticFallback(), outcome.conflicts());
            }
        };
    }
}
