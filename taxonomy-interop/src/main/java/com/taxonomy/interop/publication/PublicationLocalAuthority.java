package com.taxonomy.interop.publication;

import com.taxonomy.interop.*;
import com.taxonomy.interop.persistence.IntegrationStore.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.workspace.service.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;

/**
 * Existing repository, workspace and portfolio authorities, shared by journal guards and orchestration.
 */
@Component
public class PublicationLocalAuthority {

    public final IntegrationDomainAdapter domain;

    public final WorkspaceArchitectureIntegrationPort editor;

    private final SystemRepositoryService repositories;

    private final RepositoryMembershipService memberships;

    private final WorkspaceAccessService workspaceAccess;

    public PublicationLocalAuthority(IntegrationDomainAdapter domain, WorkspaceArchitectureIntegrationPort editor, SystemRepositoryService repositories, RepositoryMembershipService memberships, WorkspaceAccessService workspaceAccess) {
        this.domain = domain;
        this.editor = editor;
        this.repositories = repositories;
        this.memberships = memberships;
        this.workspaceAccess = workspaceAccess;
    }

    public void authorize(RepositoryContext context, boolean write, Connection connection) {
        if (context == null || context.username() == null || context.scope() != RepositoryScope.WORKSPACE) {
            throw IntegrationProblem.missing();
        }
        if (!workspaceAccess.canUsePrivateWorkspace(context)) {
            throw IntegrationProblem.missing();
        }
        var repository = repositories.getRepository(context.repositoryId());
        boolean permitted = write ? memberships.canContribute(repository, context.username())
                : memberships.canRead(repository, context.username());
        if (!permitted) {
            throw IntegrationProblem.missing();
        }
        if (connection != null && !connection.organizationId().equals(repository.getOwnerType() + ":" + repository.getOwnerId())) {
            throw IntegrationProblem.missing();
        }
        if (connection != null && connection.projectId() != null) {
            domain.requireProject(context, connection.projectId());
        }
    }

    public <T> T locked(RepositoryContext context, Function<WorkspaceArchitectureIntegrationPort.WorkspaceDocument, T> action) {
        try {
            return editor.locked(context, action);
        } catch (IOException failure) {
            throw IntegrationProblem.conflict("PUBLICATION_LOCAL_UNAVAILABLE");
        }
    }

    public void requireExact(RepositoryContext context, Connection connection, List<Identity> mappings, InternalState expected, boolean checkpoint) {
        authorize(context, true, connection);
        locked(context, document -> {
            domain.lockProject(context, connection.projectId());
            if (expected == null || !expected.equals(domain.snapshot(context, connection, mappings, document).state())) {
                throw IntegrationProblem.conflict("PUBLICATION_LOCAL_MOVED");
            }
            if (checkpoint && connection.projectId() != null && !document.dsl().equals(domain.portfolioContribution(context).apply(document.dsl()))) {
                throw IntegrationProblem.conflict("PUBLICATION_CHECKPOINT_REQUIRED");
            }
            if (checkpoint) {
                try {
                    if (!editor.isExactCheckpoint(context, document.state())) {
                        throw IntegrationProblem.conflict("PUBLICATION_CHECKPOINT_REQUIRED");
                    }
                } catch (IOException failure) {
                    throw IntegrationProblem.conflict("PUBLICATION_LOCAL_UNAVAILABLE");
                }
            }
            return null;
        });
    }
}
