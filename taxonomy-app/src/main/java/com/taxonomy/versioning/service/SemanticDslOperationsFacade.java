package com.taxonomy.versioning.service;

import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.architecture.service.CommitIndexService;
import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.portfolio.service.PortfolioGitService;
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
    private final PortfolioGitService portfolioGitService;

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
                                       PortfolioGitService portfolioGitService) {
        super(exportService, materializeService, documentRepository, repositoryFactory,
                commitIndexService, conflictDetectionService, stateGuard,
                repositoryStateService, workspaceResolver);
        this.repositoryFactory = repositoryFactory;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
        this.semanticMergeService = semanticMergeService;
        this.portfolioGitService = portfolioGitService;
    }

    @Override
    public String merge(String fromBranch, String intoBranch) throws IOException {
        WorkspaceContext context = resolveContext();
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        SemanticGitMergeService.MergeOutcome outcome = semanticMergeService.mergeBranches(
                repository, fromBranch, intoBranch, context.username());
        if (!outcome.success()) return null;
        portfolioGitService.materializeHead(intoBranch, context.username(), context);
        return outcome.commitId();
    }

    private WorkspaceContext resolveContext() {
        try {
            String username = workspaceResolver.resolveCurrentUsername();
            repositoryStateService.ensureWorkspaceState(username);
            return workspaceResolver.resolveCurrentContext();
        } catch (RuntimeException exception) {
            return WorkspaceContext.SHARED;
        }
    }
}
