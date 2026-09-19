package com.taxonomy.portfolio;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
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

import java.util.List;

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
        when(fixture.repository().getDslAtCommit("target-head")).thenReturn(targetDsl);
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
        verify(fixture.gitCore(), never()).materialize(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void previewCountsRepeatedLinesWithTheirMultiplicity() throws Exception {
        Fixture fixture = fixture();
        String targetDsl = "block {\n repeated;\n}\n";
        String currentProjection = "block {\n repeated;\n repeated;\n}\n";
        when(fixture.repository().getHeadCommit("target")).thenReturn("target-head");
        when(fixture.repository().getDslAtCommit("target-head")).thenReturn(targetDsl);
        when(fixture.gitCore().contributeTo(targetDsl, "architect", fixture.context()))
                .thenReturn(currentProjection);

        var preview = fixture.service().previewMaterialize("target", fixture.context());

        assertThat(preview.addedLines()).isZero();
        assertThat(preview.removedLines()).isEqualTo(1);
        assertThat(preview.removedPreview()).containsExactly(" repeated;");
        assertThat(preview.changed()).isTrue();
        assertThat(preview.destructiveChangePossible()).isTrue();
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
        verify(fixture.gitCore(), never()).materialize(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void exportUsesSelectedRepositoryBranchAndReportsPortfolioCounts() throws Exception {
        Fixture fixture = fixture();
        when(fixture.repository().getHeadCommit("draft")).thenReturn("head-1");
        when(fixture.gitCore().exportPortfolio("architect", fixture.context()))
                .thenReturn("portfolio dsl");

        var exported = fixture.service().export(fixture.context());

        assertThat(exported.workspaceId()).isEqualTo("ws-1");
        assertThat(exported.activeBranch()).isEqualTo("draft");
        assertThat(exported.headCommit()).isEqualTo("head-1");
        assertThat(exported.dsl()).isEqualTo("portfolio dsl");
        assertThat(exported.projectCount()).isZero();
        verify(fixture.factory()).resolveRepository(fixture.context());
    }

    @Test
    void exportDefaultsBlankSelectedBranchToDraft() throws Exception {
        Fixture fixture = fixture(new WorkspaceContext("architect", "ws-1", "  "));
        when(fixture.repository().getHeadCommit("draft")).thenReturn("head-1");
        when(fixture.gitCore().exportPortfolio("architect", fixture.context()))
                .thenReturn("portfolio dsl");

        assertThat(fixture.service().export(fixture.context()).activeBranch()).isEqualTo("draft");
    }

    @Test
    void commitReturnsParentAndResultingCounts() throws Exception {
        Fixture fixture = fixture();
        when(fixture.repository().getHeadCommit("feature")).thenReturn("parent");
        when(fixture.gitCore().commitAtHead(
                org.mockito.ArgumentMatchers.any(com.taxonomy.workspace.service.WorkspacePortfolioDocumentPort.DocumentHandle.class),
                org.mockito.ArgumentMatchers.eq("feature"), org.mockito.ArgumentMatchers.eq("parent"),
                org.mockito.ArgumentMatchers.eq("Save"), org.mockito.ArgumentMatchers.eq("architect"),
                org.mockito.ArgumentMatchers.eq(fixture.context())))
                .thenReturn(new PortfolioGitService.CommitResult("commit-2", true, "feature"));

        var result = fixture.service().commit(" feature ", "Save", fixture.context());

        assertThat(result.branch()).isEqualTo("feature");
        assertThat(result.parentCommitId()).isEqualTo("parent");
        assertThat(result.commitId()).isEqualTo("commit-2");
        assertThat(result.conflictCount()).isZero();
    }

    @Test
    void materializeAcceptsReviewedHeadAndReturnsObservedUpserts() throws Exception {
        Fixture fixture = fixture();
        when(fixture.repository().getHeadCommit("target")).thenReturn("reviewed-head");
        when(fixture.repository().getDslAtCommit("reviewed-head")).thenReturn("reviewed dsl");
        when(fixture.gitCore().materialize("reviewed dsl", "architect", fixture.context()))
                .thenReturn(new PortfolioGitService.MaterializeResult(
                        2, 3, 4, List.of("review warning")));

        var result = fixture.service().materialize(
                "target", " reviewed-head ", fixture.context());

        assertThat(result.commitId()).isEqualTo("reviewed-head");
        assertThat(result.projectsUpserted()).isEqualTo(2);
        assertThat(result.requirementsUpserted()).isEqualTo(3);
        assertThat(result.requirementVersionsCreated()).isEqualTo(4);
        assertThat(result.warnings()).isEqualTo(1);
    }

    @Test
    void mergeMaterializesSuccessfulGitMerge() throws Exception {
        Fixture fixture = fixture();
        when(fixture.repository().getHeadCommit("source")).thenReturn("source-head");
        when(fixture.repository().getHeadCommit("target")).thenReturn("target-head");
        when(fixture.mergeService().mergeBranches(
                fixture.repository(), "source", "target", "architect", "Merge portfolio"))
                .thenReturn(new SemanticGitMergeService.MergeOutcome(
                        true, "merge-head", false, List.of(), null));
        when(fixture.repository().getDslAtCommit("merge-head")).thenReturn("merged dsl");
        when(fixture.gitCore().materialize("merged dsl", "architect", fixture.context()))
                .thenReturn(new PortfolioGitService.MaterializeResult(0, 0, 0, List.of()));

        var result = fixture.service().merge(
                "source", "target", "Merge portfolio", fixture.context());

        assertThat(result.sourceHead()).isEqualTo("source-head");
        assertThat(result.targetHeadBefore()).isEqualTo("target-head");
        assertThat(result.mergeCommitId()).isEqualTo("merge-head");
        assertThat(result.strategy()).isEqualTo("GIT");
        verify(fixture.gitCore()).materialize("merged dsl", "architect", fixture.context());
    }

    @Test
    void mergeReportsSemanticFallbackAndRejectsUnresolvedConflict() throws Exception {
        Fixture fixture = fixture();
        when(fixture.repository().getHeadCommit("source")).thenReturn("source-head");
        when(fixture.repository().getHeadCommit("target")).thenReturn("target-head");
        when(fixture.mergeService().mergeBranches(
                fixture.repository(), "source", "target", "architect", "semantic"))
                .thenReturn(new SemanticGitMergeService.MergeOutcome(
                        true, "semantic-head", true, List.of(), null));
        when(fixture.repository().getDslAtCommit("semantic-head")).thenReturn("semantic dsl");
        when(fixture.gitCore().materialize("semantic dsl", "architect", fixture.context()))
                .thenReturn(new PortfolioGitService.MaterializeResult(0, 0, 0, List.of()));

        assertThat(fixture.service().merge(
                "source", "target", "semantic", fixture.context()).strategy())
                .isEqualTo("SEMANTIC_FALLBACK");

        when(fixture.mergeService().mergeBranches(
                fixture.repository(), "source", "target", "architect", "blocked"))
                .thenReturn(new SemanticGitMergeService.MergeOutcome(
                        false, null, true, List.of("project P-1"), null));
        assertThatThrownBy(() -> fixture.service().merge(
                "source", "target", "blocked", fixture.context()))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("project P-1");
    }

    @Test
    void operationsRejectUnsafeBranchesAndMissingHeadsBeforeMutation() throws Exception {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service().commit("bad branch", "save", fixture.context()))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Invalid Git branch name");
        assertThatThrownBy(() -> fixture.service().merge(
                "same", "same", "save", fixture.context()))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("must differ");
        when(fixture.repository().getHeadCommit("empty")).thenReturn(null);
        assertThatThrownBy(() -> fixture.service().previewMaterialize("empty", fixture.context()))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("has no commits");
        verify(fixture.gitCore(), never()).commitAtHead(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(fixture.gitCore(), never()).commit(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private static Fixture fixture() {
        return fixture(new WorkspaceContext("architect", "ws-1", "draft"));
    }

    private static Fixture fixture(WorkspaceContext context) {
        PortfolioGitService gitCore = mock(PortfolioGitService.class);
        ProjectPortfolioService projects = mock(ProjectPortfolioService.class);
        SolutionPortfolioService solutions = mock(SolutionPortfolioService.class);
        ProductCatalogService products = mock(ProductCatalogService.class);
        ProjectConflictService conflicts = mock(ProjectConflictService.class);
        DslGitRepositoryFactory factory = mock(DslGitRepositoryFactory.class);
        SemanticGitMergeService mergeService = mock(SemanticGitMergeService.class);
        DslGitRepository repository = mock(DslGitRepository.class);
        when(factory.resolveRepository(context)).thenReturn(repository);
        when(repository.getGitRepository()).thenReturn(mock(org.eclipse.jgit.lib.Repository.class));
        when(projects.listProjects("architect", context)).thenReturn(List.of());
        when(solutions.listSolutions("architect", context)).thenReturn(List.of());
        when(products.listProducts("architect", context)).thenReturn(List.of());
        PortfolioGitApplicationService service = new PortfolioGitApplicationService(gitCore,
                projects,
                solutions,
                products,
                conflicts,
                new com.taxonomy.workspace.storage.WorkspacePortfolioDocumentAdapter(factory, mergeService, publicationVersionsFixture()));
        return new Fixture(service, gitCore, repository, factory, mergeService, context);
    }

    private record Fixture(PortfolioGitApplicationService service,
                           PortfolioGitService gitCore,
                           DslGitRepository repository,
                           DslGitRepositoryFactory factory,
                           SemanticGitMergeService mergeService,
                           WorkspaceContext context) {
    }

    private static com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort publicationVersionsFixture() {
        return new com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort() {
            @Override
            public <T> T version(com.taxonomy.workspace.service.RepositoryContext context, String rationale,
                    GitAction<T> action) throws java.io.IOException {
                return action.run();
            }
        };
    }
}
