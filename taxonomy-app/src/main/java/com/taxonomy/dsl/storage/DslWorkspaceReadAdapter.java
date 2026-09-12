package com.taxonomy.dsl.storage;

import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Adapts exact DSL/branch reads without normalizing missing files to empty DSL. */
@Component
public final class DslWorkspaceReadAdapter implements WorkspaceDslReadPort {

    private final DslGitRepositoryFactory repositories;

    public DslWorkspaceReadAdapter(DslGitRepositoryFactory repositories) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
    }

    @Override
    public RepositoryRead openRead(RepositoryContext context) {
        Objects.requireNonNull(context, "context");
        return new BoundRead(repositories.resolveRepository(context), context.branch());
    }

    private static final class BoundRead implements RepositoryRead {
        private final DslGitRepository repository;
        private final String branch;
        private final ExpectedHeadDslCommitter verifier = new ExpectedHeadDslCommitter();

        private BoundRead(DslGitRepository repository, String branch) {
            this.repository = Objects.requireNonNull(repository, "repository");
            this.branch = branch;
        }

        @Override
        public String currentHead() throws IOException {
            return repository.getHeadCommit(branch);
        }

        @Override
        public Optional<String> dslAtCommit(String commitId) throws IOException {
            Objects.requireNonNull(commitId, "commitId");
            return Optional.ofNullable(repository.getDslAtCommit(commitId));
        }

        @Override
        public String verifyExpectedHead(String expectedHeadCommit) throws IOException {
            return verifier.verifyExpectedHead(repository, branch, expectedHeadCommit);
        }
    }
}
