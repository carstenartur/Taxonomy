package com.taxonomy.portfolio;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.portfolio.service.PortfolioGitApplicationService;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.ProductCatalogService;
import com.taxonomy.portfolio.service.ProjectConflictService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.SolutionPortfolioService;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioGitMaterializationPreviewTest {

    @Test
    void previewReportsAddedAndRemovedLinesWithoutMaterializing() throws Exception {
        PortfolioGitService gitCore = mock(PortfolioGitService.class);
        ProjectPortfolioService projects = mock(ProjectPortfolioService.class);
        SolutionPortfolioService solutions = mock(SolutionPortfolioService.class);
        ProductCatalogService products = mock(ProductCatalogService.class);
        ProjectConflictService conflicts = mock(ProjectConflictService.class);
        DslGitRepositoryFactory factory = mock(DslGitRepositoryFactory.class);
        SemanticGitMergeService mergeService = mock(SemanticGitMergeService.class);
        DslGitRepository repository = mock(DslGitRepository.class);
        WorkspaceContext context = new WorkspaceContext("architect", "ws-1", "draft");
        String targetDsl = "meta {\n version: \"2.0\";\n}\nrequirement NEW {}\n";
        String currentProjection =
                "meta {\n version: \"2.0\";\n}\nrequirement OLD {}\n";

        when(factory.resolveRepository(context)).thenReturn(repository);
        when(repository.getHeadCommit("target")).thenReturn("target-head");
        when(repository.getDslAtHead("target")).thenReturn(targetDsl);
        when(gitCore.contributeTo(targetDsl, "architect", context))
                .thenReturn(currentProjection);
        PortfolioGitApplicationService service = new PortfolioGitApplicationService(
                gitCore, projects, solutions, products, conflicts, factory, mergeService);

        var preview = service.previewMaterialize("target", context);

        assertThat(preview.targetHead()).isEqualTo("target-head");
        assertThat(preview.changed()).isTrue();
        assertThat(preview.destructiveChangePossible()).isTrue();
        assertThat(preview.addedPreview()).contains("requirement NEW {}");
        assertThat(preview.removedPreview()).contains("requirement OLD {}");
        verify(gitCore, never()).materializeHead(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}
