package com.taxonomy.portfolio;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.ExportedPortfolioDsl;
import com.taxonomy.portfolio.service.PortfolioDslMaterializationService;
import com.taxonomy.portfolio.service.PortfolioGitApplicationService;
import com.taxonomy.portfolio.service.PortfolioGitProjectionService;
import com.taxonomy.versioning.service.SemanticDslOperationsFacade;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioGitMaterializationPreviewTest {

    @Test
    void previewReportsAddedAndRemovedLinesWithoutMaterializing() {
        PortfolioGitProjectionService projection = mock(PortfolioGitProjectionService.class);
        PortfolioDslMaterializationService materialization =
                mock(PortfolioDslMaterializationService.class);
        DslGitRepositoryFactory factory = mock(DslGitRepositoryFactory.class);
        SemanticDslOperationsFacade dsl = mock(SemanticDslOperationsFacade.class);
        DslGitRepository repository = mock(DslGitRepository.class);
        WorkspaceContext context = new WorkspaceContext("architect", "ws-1", "draft");
        when(projection.exportDsl(context)).thenReturn(new ExportedPortfolioDsl(
                "ws-1", "architect", "draft", "old-head",
                "meta {\n version: \"2.0\";\n}\nrequirement OLD {}\n",
                1, 1, 0, 0, Instant.now()));
        when(dsl.getHead("target", context)).thenReturn(
                "meta {\n version: \"2.0\";\n}\nrequirement NEW {}\n");
        when(factory.resolveRepository(context)).thenReturn(repository);
        when(repository.getHeadCommit("target")).thenReturn("target-head");
        PortfolioGitApplicationService service = new PortfolioGitApplicationService(
                projection, materialization, factory, dsl);

        var preview = service.previewMaterialize("target", context);

        assertThat(preview.targetHead()).isEqualTo("target-head");
        assertThat(preview.changed()).isTrue();
        assertThat(preview.destructiveChangePossible()).isTrue();
        assertThat(preview.addedPreview()).contains("requirement NEW {}");
        assertThat(preview.removedPreview()).contains("requirement OLD {}");
        verify(materialization, never()).materialize(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}
