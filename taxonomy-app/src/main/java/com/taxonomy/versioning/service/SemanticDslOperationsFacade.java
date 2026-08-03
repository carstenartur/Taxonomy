package com.taxonomy.versioning.service;

import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.architecture.service.CommitIndexService;
import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.service.RepositoryStateGuard;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** DslOperationsFacade variant that executes semantic DSL merges on conflicts. */
@Component
@Primary
public class SemanticDslOperationsFacade extends DslOperationsFacade {

    private final DslGitRepositoryFactory repositoryFactory;
    private final RepositoryStateService repositoryStateService;
    private final WorkspaceResolver workspaceResolver;
    private final SemanticGitMergeService semanticMergeService;
    private final VersioningPortfolioGitPort portfolioGitPort;

    public SemanticDslOperationsFacade(TaxDslExportService exportService,
                                       DslMaterializeService materializeService,
                                       ArchitectureDslDocumentRepository documentRepository,
                                       DslGitRepositoryFactory repositoryFactory,
                                       CommitIndexService commitIndexService,
                                       ConflictDetectionService conflictDetectionService,
                                       RepositoryStateGuard stateGuard,
                                       RepositoryStateService repositoryStateService,
                                       WorkspaceResolver workspaceResolver,
                                       SemanticGitMergeService semanticMergeService,
                                       VersioningPortfolioGitPort portfolioGitPort) {
        super(exportService, materializeService, documentRepository, repositoryFactory,
                commitIndexService, conflictDetectionService, stateGuard,
                repositoryStateService, workspaceResolver);
        this.repositoryFactory = repositoryFactory;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
        this.semanticMergeService = semanticMergeService;
        this.portfolioGitPort = portfolioGitPort;
    }

    @Override
    public String merge(String fromBranch, String intoBranch) throws IOException {
        WorkspaceContext context = resolveContext();
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        SemanticGitMergeService.MergeOutcome outcome = semanticMergeService.mergeBranches(
                repository, fromBranch, intoBranch, context.username());
        if (!outcome.success()) return null;
        portfolioGitPort.materializePortfolioHead(
                intoBranch, context.username(), context);
        return outcome.commitId();
    }

    /**
     * Resolves the request-bound workspace and fails closed when provisioning or
     * context lookup fails. A silent shared fallback would make a semantic merge
     * cross the user's repository boundary.
     */
    private WorkspaceContext resolveContext() {
        String username = workspaceResolver.resolveCurrentUsername();
        repositoryStateService.ensureWorkspaceState(username);
        return workspaceResolver.resolveCurrentContext();
    }
}
