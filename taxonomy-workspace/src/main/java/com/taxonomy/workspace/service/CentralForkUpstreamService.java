package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** Fetches only the upstream recorded by the central fork's provisioning operation. */
@Service
public class CentralForkUpstreamService {
    private final SystemRepositoryService catalogue;
    private final RepositoryMembershipService memberships;
    private final DslGitRepositoryFactory repositories;

    public CentralForkUpstreamService(SystemRepositoryService catalogue, RepositoryMembershipService memberships,
                                      DslGitRepositoryFactory repositories) {
        this.catalogue = catalogue;
        this.memberships = memberships;
        this.repositories = repositories;
    }

    public WorkspaceUpstreamService.Status inspect(String forkId, String actor) throws IOException {
        return open(forkId, actor).inspect();
    }

    public WorkspaceUpstreamService.Status fetch(String forkId, String actor, WorkspaceUpstreamService.Request request)
            throws IOException {
        return open(forkId, actor).fetch(request);
    }

    public WorkspaceUpstreamService.TagStatus inspectTag(String forkId, String actor, String name) throws IOException {
        return open(forkId, actor).inspectTag(name);
    }

    public WorkspaceUpstreamService.TagStatus fetchTag(String forkId, String actor, WorkspaceUpstreamService.TagRequest request)
            throws IOException {
        return open(forkId, actor).fetchTag(request);
    }

    public RepositoryContext context(String forkId, String actor) {
        var origin = authorizedBinding(forkId, actor);
        return RepositoryContext.centralWrite(forkId, origin.upstream().branch(), actor);
    }

    WorkspaceUpstreamService.Capture capture(RepositoryContext selected) throws IOException {
        var transfer = open(selected.repositoryId(), selected.username());
        return new WorkspaceUpstreamService.Capture(transfer.inspect(), transfer.forkPoint());
    }

    GitUpstreamTransfer recorded(RepositoryFetchOperation operation) throws IOException {
        var selected = operation.context();
        var transfer = open(selected.repositoryId(), selected.username());
        transfer.requireOrigin(operation.sourceRepositoryId(), operation.sourceBranch, operation.forkPoint);
        return transfer;
    }

    private record Binding(GitUpstreamRef upstream, String forkPoint) {}

    private GitUpstreamTransfer open(String forkId, String actor) {
        Binding binding = authorizedBinding(forkId, actor);
        return new GitUpstreamTransfer(binding.upstream(),
                repositories.getCentralRepository(binding.upstream().repositoryId()), repositories.getCentralRepository(forkId),
                binding.forkPoint(), actor, () -> {
                    if (!binding.equals(authorizedBinding(forkId, actor))) throw unavailable();
                });
    }

    private Binding authorizedBinding(String forkId, String actor) {
        try {
            return catalogue.readCurrentForkAuthorization(forkId, (fork, source) -> {
                String forkPoint = fork.getForkPointCommit();
                if (!memberships.canContribute(fork, actor) || !memberships.canContribute(source, actor)
                        || fork.getVisibility() == null || forkPoint == null || !ObjectId.isId(forkPoint)) throw unavailable();
                SystemRepositoryService.requirePermittedForkVisibility(source, fork.getVisibility());
                return new Binding(new GitUpstreamRef(fork.getUpstreamRepositoryId(), fork.getUpstreamBranch()), forkPoint);
            });
        } catch (IllegalArgumentException unavailableOrRestricted) {
            throw unavailable();
        }
    }

    private static AccessDeniedException unavailable() {
        return new AccessDeniedException("The selected central fork or its upstream is unavailable");
    }
}
