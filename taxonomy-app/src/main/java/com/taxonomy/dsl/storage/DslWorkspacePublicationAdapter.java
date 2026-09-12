package com.taxonomy.dsl.storage;

import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceDslPublicationPort;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;

/** Preserves generated-snapshot append semantics behind the workspace-owned API. */
@Component
public final class DslWorkspacePublicationAdapter implements WorkspaceDslPublicationPort {

    private final DslGitRepositoryFactory repositories;

    public DslWorkspacePublicationAdapter(DslGitRepositoryFactory repositories) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
    }

    @Override
    public String publishSnapshot(RepositoryContext context, String targetBranch,
                                  String dslText, String message) throws IOException {
        Objects.requireNonNull(context, "context");
        if (context.scope() == RepositoryScope.CENTRAL_READ) {
            throw new IllegalStateException("Cannot publish a DSL snapshot to a read-only repository context");
        }
        if (targetBranch == null || targetBranch.isBlank()) {
            throw new IllegalArgumentException("targetBranch must not be blank");
        }
        Objects.requireNonNull(dslText, "dslText");
        DslGitRepository repository = repositories.resolveRepository(context);
        return repository.commitDsl(targetBranch, dslText, context.username(), message);
    }
}
