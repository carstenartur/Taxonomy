package com.taxonomy.portfolio;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.portfolio.service.PortfolioException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioGitMaterializationPreviewTest {

    @Test
    void previewReportsAddedAndRemovedLinesWithoutMaterializing() throws Exception {
        Fixture fixture = fixture();
        String targetDsl = "meta {\n version: \"2.0\";\n}\nrequirement NEW {}\n";
        String currentProjection =
                "meta {\n version: \"2.0\";\n}\nrequirement OLD {}\n";
        when(fixture.repository().getHeadCommit("target")).thenReturn("target-head");
        when(fixture.repository().getDslAtHead("target")).thenReturn(targetDsl);
        when(fixture.gitCore().contributeTo(targetDsl, "architect", fixture.context()))
                .thenReturn(currentProjection);

        var preview = fixture.service().previewMaterialize("target", fixture.context());

        assertThat(preview.targetHead()).isEqualTo("target-head");
        assertThat(preview.changed()).isTrue();
        assertThat(preview.destructiveChangePossible()).isTrue();
        assertThat(preview.addedPreview()).contains("requirement NEW {}");
        assertThat(preview.removedPreview()).contains("requirement OLD {}");
        verify(fixture.gitCore(), never()).materializeHead(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void materializationRejectsAHeadThatChangedAfterReview() throws Exception {
        Fixture fixture = fixture();
        when(fixture.repository().getHeadCommit("target")).thenReturn("new-head");

        assertThatThrownBy(() -> fixture.service().materialize(
                "target", "reviewed-head", fixture.context()))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Branch changed after materialization preview")
                .hasMessageContaining("reviewed-head")
                .hasMessageContaining("new-head");

        verify(fixture.gitCore(), never()).materializeHead(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private static Fixture fixture() {
        PortfolioGitService gitCore = mock(PortfolioGitService.class);
        ProjectPortfolioService projects = mock(ProjectPortfolioService.class);
        SolutionPortfolioService solutions = mock(SolutionPortfolioService.class);
        ProductCatalogService products = mock(ProductCatalogService.class);
        ProjectConflictService conflicts = mock(ProjectConflictService.class);
        DslGitRepositoryFactory factory = mock(DslGitRepositoryFactory.class);
        SemanticGitMergeService mergeService = mock(SemanticGitMergeService.class);
        DslGitRepository repository = mock(DslGitRepository.class);
        WorkspaceContext context = new WorkspaceContext("architect", "ws-1", "draft");
        when(factory.resolveRepository(context)).thenReturn(repository);
        PortfolioGitApplicationService service = new PortfolioGitApplicationService(
                gitCore, projects, solutions, products, conflicts, factory, mergeService);
        return new Fixture(service, gitCore, repository, context);
    }

    private record Fixture(PortfolioGitApplicationService service,
                           PortfolioGitService gitCore,
                           DslGitRepository repository,
                           WorkspaceContext context) {
    }
}
