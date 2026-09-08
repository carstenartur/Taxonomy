package com.taxonomy.versioning.service;

import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.architecture.service.CommitIndexService;
import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.service.RepositoryStateGuard;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DslOperationsFacadeWorkspaceIsolationTest {

    @Test
    void doesNotFallBackToSharedRepositoryWhenWorkspaceProvisioningFails() {
        TaxDslExportService exportService = mock(TaxDslExportService.class);
        DslMaterializeService materializeService = mock(DslMaterializeService.class);
        ArchitectureDslDocumentRepository documentRepository = mock(ArchitectureDslDocumentRepository.class);
        DslGitRepositoryFactory repositoryFactory = mock(DslGitRepositoryFactory.class);
        CommitIndexService commitIndexService = mock(CommitIndexService.class);
        ConflictDetectionService conflictDetectionService = mock(ConflictDetectionService.class);
        RepositoryStateGuard stateGuard = mock(RepositoryStateGuard.class);
        RepositoryStateService repositoryStateService = mock(RepositoryStateService.class);
        WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);

        DslOperationsFacade facade = new DslOperationsFacade(
                exportService,
                materializeService,
                documentRepository,
                repositoryFactory,
                commitIndexService,
                conflictDetectionService,
                stateGuard,
                repositoryStateService,
                workspaceResolver, new com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort() { public <T> T version(com.taxonomy.workspace.service.RepositoryContext c, String r, GitAction<T> a) throws java.io.IOException { return a.run(); } });

        when(workspaceResolver.resolveCurrentUsername()).thenReturn("architect");
        doThrow(new IllegalStateException("workspace database unavailable"))
                .when(repositoryStateService).ensureWorkspaceState("architect");

        assertThatThrownBy(() -> facade.getDslHistory("draft"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workspace database unavailable");
        verifyNoInteractions(repositoryFactory);
    }
}
