package com.taxonomy.dsl.storage;

import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceDslVersionPort;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;

/**
 * Adapts the existing repository factory and CAS committer to the workspace API.
 * Storage lifecycle, routing and ref-update semantics remain with their existing owners.
 */
@Component
public final class DslWorkspaceVersionAdapter implements WorkspaceDslVersionPort {

    private final DslGitRepositoryFactory repositories;

    public DslWorkspaceVersionAdapter(DslGitRepositoryFactory repositories) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
    }

    @Override
    public ExactVersion openVersion(RepositoryContext context, String expectedHeadCommit) {
        Objects.requireNonNull(context, "context");
        String normalizedHead = normalizeExpectedHead(expectedHeadCommit);
        return new BoundVersion(repositories.resolveRepository(context), context, normalizedHead);
    }

    private static String normalizeExpectedHead(String expectedHeadCommit) {
        if (expectedHeadCommit == null) {
            return null;
        }
        String normalized = expectedHeadCommit.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "expectedHeadCommit must be null or a full commit ID");
        }
        ObjectId.fromString(normalized);
        return normalized;
    }

    private static final class BoundVersion implements ExactVersion {
        private final DslGitRepository repository;
        private final RepositoryContext context;
        private final String expectedHead;
        private final ExpectedHeadDslCommitter committer = new ExpectedHeadDslCommitter();

        private BoundVersion(DslGitRepository repository, RepositoryContext context, String expectedHead) {
            this.repository = Objects.requireNonNull(repository, "repository");
            this.context = context;
            this.expectedHead = expectedHead;
        }

        @Override
        public String expectedHeadCommit() {
            return expectedHead;
        }

        @Override
        public String readDsl() throws IOException {
            if (expectedHead == null) {
                return "";
            }
            String dsl = repository.getDslAtCommit(expectedHead);
            return dsl == null ? "" : dsl;
        }

        @Override
        public String verifyExpectedHead() throws IOException {
            return committer.verifyExpectedHead(repository, context.branch(), expectedHead);
        }

        @Override
        public CommitResult commit(String dslText, String message) throws IOException {
            if (context.scope() == RepositoryScope.CENTRAL_READ) {
                throw new IllegalStateException("Cannot commit a read-only repository context");
            }
            var result = committer.commit(repository, new ExpectedHeadDslCommitter.CommitRequest(
                    context.branch(), expectedHead, dslText, context.username(), message));
            return new CommitResult(result.commitId(), result.previousHeadCommit());
        }
    }
}
