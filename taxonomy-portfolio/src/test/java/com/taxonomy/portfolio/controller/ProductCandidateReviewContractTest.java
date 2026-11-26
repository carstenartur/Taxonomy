package com.taxonomy.portfolio.controller;

import com.taxonomy.catalog.api.TaxonomyNodeLookup;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.ProductCatalogEntry;
import com.taxonomy.portfolio.model.ProjectSolution;
import com.taxonomy.portfolio.model.SolutionProductCandidate;
import com.taxonomy.portfolio.model.SolutionDefinition;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertProductCandidateRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductSelectionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProductCatalogEntryRepository;
import com.taxonomy.portfolio.repository.ProductTaxonomyCoverageRepository;
import com.taxonomy.portfolio.repository.ProjectConflictRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.repository.ProjectSolutionRepository;
import com.taxonomy.portfolio.repository.SolutionProductCandidateRepository;
import com.taxonomy.portfolio.reformulation.ReformulationPositiveReviewGuard;
import com.taxonomy.portfolio.service.PortfolioFingerprintService;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProductCatalogService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.taxonomy.portfolio.model.PortfolioTypes.ProductSelectionStatus.CANDIDATE;
import static com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus.PROPOSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductCandidateReviewContractTest {

    private static final String CONFIRM = """
            {"reviewStatus":"CONFIRMED","selectionStatus":"SHORTLISTED"}
            """;
    private static final String SELECT = """
            {"reviewStatus":"CONFIRMED","selectionStatus":"SELECTED"}
            """;

    private final WorkspaceContext context = new WorkspaceContext("alice", "ws-a", "review", "repo-a");
    private final String scope = PortfolioScope.key("alice", context);
    private final ArchitectureProjectRepository projects = mock(ArchitectureProjectRepository.class);
    private final SolutionProductCandidateRepository candidates = mock(SolutionProductCandidateRepository.class);
    private final ProductCatalogEntryRepository products = mock(ProductCatalogEntryRepository.class);
    private final ProjectSolutionRepository projectSolutions = mock(ProjectSolutionRepository.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);

    private ProductCatalogEntry product;
    private ProjectSolution projectSolution;
    private SolutionProductCandidate candidate;
    private MockMvc mvc;
    private ProductCatalogService productService;

    @BeforeEach
    void setUp() {
        ArchitectureProject project = project(1L, "PROJECT-A");
        var solution = new SolutionDefinition(scope, "ws-a", "SOLUTION-A", "Solution A",
                null, null, null, null, 1, "editor", null, Instant.EPOCH);
        projectSolution = new ProjectSolution(project, solution,
                null, null, 3, "Replaces legacy equipment", "editor", Instant.EPOCH);
        ReflectionTestUtils.setField(projectSolution, "id", 3L);
        product = product(context);
        givenCandidate("Manual exclusion", "Retained inventory exclusion");

        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(context);
        var coverage = mock(ProductTaxonomyCoverageRepository.class);
        when(coverage.findByProductIdOrderByNodeCodeAsc(9L)).thenReturn(List.of());
        when(products.findByIdAndScopeKey(9L, scope)).thenReturn(Optional.of(product));
        when(projectSolutions.findByIdAndProjectId(3L, 1L)).thenReturn(Optional.of(projectSolution));
        when(candidates.save(any())).thenAnswer(call -> {
            SolutionProductCandidate value = call.getArgument(0);
            if (value.getId() == null) ReflectionTestUtils.setField(value, "id", 18L);
            return value;
        });
        var projectService = new ProjectPortfolioService(projects, mock(ProjectRequirementRepository.class),
                mock(ProjectRequirementVersionRepository.class), projectSolutions, mock(ProjectConflictRepository.class),
                mock(PortfolioFingerprintService.class), mock(PortfolioJsonCodec.class),
                mock(ReformulationPositiveReviewGuard.class));
        productService = new ProductCatalogService(products, coverage,
                candidates, projectSolutions, mock(TaxonomyNodeLookup.class), projectService);
        mvc = MockMvcBuilders.standaloneSetup(new ProductCatalogController(productService, resolver))
                .setControllerAdvice(new PortfolioExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"productId\":9,\"coveragePercent\":42}",
            "{\"productId\":9,\"coveragePercent\":42,\"confidence\":null}",
            "{\"productId\":9,\"coveragePercent\":42,\"confidence\":0.0}"
    })
    void browserCanCreateFortyTwoPercentCandidateWithoutInventingConfidence(String body) throws Exception {
        upsert(body).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(18))
                .andExpect(jsonPath("$.coveragePercent").value(42))
                .andExpect(jsonPath("$.confidence").value(0.0))
                .andExpect(jsonPath("$.reviewStatus").value("PROPOSED"))
                .andExpect(jsonPath("$.selectionStatus").value("CANDIDATE"));
        verify(candidates).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"productId\":9}", "{\"productId\":9,\"coveragePercent\":null,\"confidence\":null}"})
    void newCandidateStillRequiresAnExplicitCoveragePercentage(String body) throws Exception {
        upsert(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("coveragePercent is required for a new product candidate"));
        verify(candidates, never()).save(any());
    }

    @Test
    void partialCandidatePostKeepsEvidenceAndDecisionsWhileAcceptingExplicitZero() throws Exception {
        submit(1L, 17L, CONFIRM).andExpect(status().isOk());
        when(candidates.findByProjectSolutionIdAndProductId(3L, 9L)).thenReturn(Optional.of(candidate));
        upsert("{\"productId\":9,\"coveragePercent\":0,\"confidence\":0.0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.coveragePercent").value(0))
                .andExpect(jsonPath("$.confidence").value(0.0))
                .andExpect(jsonPath("$.reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.selectionStatus").value("SHORTLISTED"));
        assertThat(candidate.getHardExclusions()).isEqualTo("Manual exclusion");
        assertThat(candidate.getStrengths()).isEqualTo("Vendor strength");
        assertThat(candidate.getWeaknesses()).isEqualTo("Dependency to check");
        assertThat(candidate.getOpenEvidence()).isEqualTo("Independent source");
        assertThat(candidate.getInventoryHardExclusions()).isEqualTo("Retained inventory exclusion");
    }

    @Test
    void omittedCandidateNumbersKeepTheirStoredValues() throws Exception {
        when(candidates.findByProjectSolutionIdAndProductId(3L, 9L)).thenReturn(Optional.of(candidate));
        upsert("{\"productId\":9,\"reviewStatus\":\"REJECTED\",\"selectionStatus\":\"REJECTED\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.coveragePercent").value(73))
                .andExpect(jsonPath("$.confidence").value(0.81));
        assertThat(candidate.getStrengths()).isEqualTo("Vendor strength");
        assertThat(candidate.getHardExclusions()).isEqualTo("Manual exclusion");
    }

    @Test
    void candidatePostCannotDropAnOmittedManualExclusionToAllowSelection() throws Exception {
        givenCandidate("Manual exclusion", null);
        when(candidates.findByProjectSolutionIdAndProductId(3L, 9L)).thenReturn(Optional.of(candidate));
        upsert("{\"productId\":9,\"coveragePercent\":42,\"confidence\":0.0,\"reviewStatus\":\"CONFIRMED\",\"selectionStatus\":\"SELECTED\"}")
                .andExpect(status().isBadRequest());
        assertUnchanged();
        assertThat(candidate.getHardExclusions()).isEqualTo("Manual exclusion");
    }

    @Test
    void manualPostWithTheSameInventoryWordingStillRequiresExplicitClearing() throws Exception {
        givenCandidate(null, "Retained inventory exclusion");
        when(candidates.findByProjectSolutionIdAndProductId(3L, 9L)).thenReturn(Optional.of(candidate));
        upsert("""
                {"productId":9,"hardExclusions":"Retained inventory exclusion",
                 "reviewStatus":"CONFIRMED","selectionStatus":"SHORTLISTED"}
                """).andExpect(status().isOk());
        var request = new UpsertProductCandidateRequest(9L, 42, null, null, null, "New inventory review", 0.0,
                ReviewStatus.CONFIRMED, ProductSelectionStatus.SELECTED);
        assertThatThrownBy(() -> productService.applyReviewedInventoryCandidate(1L, 3L, request,
                "new-comparison", "new-fingerprint", "", "alice", context))
                .isInstanceOf(PortfolioException.class).hasMessageContaining("hard exclusions");
        assertThat(candidate.getHardExclusions()).isEqualTo("Retained inventory exclusion");
        assertThat(candidate.getInventoryComparisonId()).isEqualTo("comparison-1");
        assertThat(candidate.getInventoryHardExclusions()).isEqualTo("Retained inventory exclusion");
        upsert("{\"productId\":9,\"hardExclusions\":\"\"}").andExpect(status().isOk());
        var result = productService.applyReviewedInventoryCandidate(1L, 3L, request,
                "new-comparison", "new-fingerprint", "", "alice", context);
        assertThat(result.selectionStatus()).isEqualTo(ProductSelectionStatus.SELECTED);
        assertThat(result.hardExclusions()).isNullOrEmpty();
    }

    @ParameterizedTest
    @CsvSource({"CONFIRMED, SHORTLISTED", "REJECTED, REJECTED"})
    void browserReviewUpdatesExistingCandidateWithoutChangingEvidence(String review, String selection) throws Exception {
        submit(1L, 17L, """
                {"reviewStatus":"%s","selectionStatus":"%s"}
                """.formatted(review, selection))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(17))
                .andExpect(jsonPath("$.projectSolutionId").value(3))
                .andExpect(jsonPath("$.product.id").value(9))
                .andExpect(jsonPath("$.reviewStatus").value(review))
                .andExpect(jsonPath("$.selectionStatus").value(selection))
                .andExpect(jsonPath("$.updatedBy").value("alice"));

        verify(candidates).save(candidate);
        assertThat(candidate.getProjectSolution()).isSameAs(projectSolution);
        assertThat(candidate.getProduct()).isSameAs(product);
        assertThat(candidate.getCoveragePercent()).isEqualTo(73);
        assertThat(candidate.getConfidence()).isEqualTo(0.81);
        assertThat(candidate.getStrengths()).isEqualTo("Vendor strength");
        assertThat(candidate.getWeaknesses()).isEqualTo("Dependency to check");
        assertThat(candidate.getOpenEvidence()).isEqualTo("Independent source");
        assertThat(candidate.getHardExclusions()).isEqualTo("Manual exclusion");
        assertThat(candidate.getInventoryComparisonId()).isEqualTo("comparison-1");
        assertThat(candidate.getInventoryFingerprint()).isEqualTo("fingerprint-1");
        assertThat(candidate.getInventoryHardExclusions()).isEqualTo("Retained inventory exclusion");
        assertThat(candidate.getUpdatedAt()).isAfter(Instant.EPOCH);
    }

    @Test
    void partialReviewKeepsTheOtherStoredStatus() throws Exception {
        submit(1L, 17L, "{\"reviewStatus\":\"CONFIRMED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionStatus").value("CANDIDATE"));
        submit(1L, 17L, "{\"selectionStatus\":\"SHORTLISTED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("CONFIRMED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reviewStatus\":null,\"selectionStatus\":null}",
            "{\"reviewStatus\":\"APPROVED\"}", "{\"selectionStatus\":\"APPROVED\"}",
            "{\"reviewStatus\":1}", "{\"selectionStatus\":2}"})
    void emptyAndInvalidStatusRequestsCannotSave(String body) throws Exception {
        submit(1L, 17L, body).andExpect(status().isBadRequest());
        assertUnchanged();
    }

    @Test
    void missingCandidateInTheAuthorizedProjectReturnsNotFound() throws Exception {
        submit(1L, 18L, CONFIRM)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:taxonomy:portfolio:not_found"));
        assertUnchanged();
    }

    @Test
    void anotherAuthorizedProjectCannotReviewThisCandidate() throws Exception {
        project(2L, "PROJECT-B");
        submit(2L, 17L, CONFIRM)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:taxonomy:portfolio:not_found"));
        assertUnchanged();
    }

    @Test
    void aDifferentWorkspaceCannotReviewTheProjectCandidate() throws Exception {
        when(resolver.resolveCurrentContext()).thenReturn(new WorkspaceContext("alice", "ws-b", "review", "repo-b"));
        submit(1L, 17L, CONFIRM)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:taxonomy:portfolio:not_found"));
        assertUnchanged();
    }

    @Test
    void anAuthorizedProjectCannotReviewACandidateWithAnOutOfScopeProduct() throws Exception {
        product = product(new WorkspaceContext("alice", "ws-b", "review", "repo-b"));
        givenCandidate("Manual exclusion", "Retained inventory exclusion");
        submit(1L, 17L, CONFIRM)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:taxonomy:portfolio:not_found"))
                .andExpect(jsonPath("$.detail").value("Product candidate not found: 17"));
        assertUnchanged();
    }

    @ParameterizedTest
    @CsvSource(value = {"Manual exclusion|", "|Retained inventory exclusion"}, delimiter = '|')
    void eitherSourceOfHardExclusionsPreventsSelection(String manual, String retained) throws Exception {
        givenCandidate(manual, retained);
        submit(1L, 17L, SELECT).andExpect(status().isBadRequest());
        assertUnchanged();
    }

    @Test
    void selectingWithoutConfirmedReviewIsRejected() throws Exception {
        givenCandidate(null, null);
        submit(1L, 17L, "{\"selectionStatus\":\"SELECTED\"}").andExpect(status().isBadRequest());
        assertUnchanged();
    }

    @Test
    void aConfirmedCandidateWithoutExclusionsCanBeExplicitlySelected() throws Exception {
        givenCandidate(null, null);
        submit(1L, 17L, SELECT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionStatus").value("SELECTED"));
        verify(candidates).save(candidate);
    }

    private ArchitectureProject project(Long id, String key) {
        var project = new ArchitectureProject(scope, "ws-a", "alice", key, key, null, null, Instant.EPOCH);
        ReflectionTestUtils.setField(project, "id", id);
        when(projects.findByIdAndScopeKey(id, scope)).thenReturn(Optional.of(project));
        return project;
    }

    private ProductCatalogEntry product(WorkspaceContext ownerContext) {
        var entry = new ProductCatalogEntry(PortfolioScope.key("alice", ownerContext), ownerContext.workspaceId(),
                "PRODUCT-A", "Vendor", null, "Product A", null, null, null, null, null, null, null, null, null,
                null, null, "Vendor documentation", Instant.EPOCH, "editor", Instant.EPOCH);
        ReflectionTestUtils.setField(entry, "id", 9L);
        return entry;
    }

    private void givenCandidate(String manual, String retained) {
        candidate = new SolutionProductCandidate(projectSolution, product, 73, manual, "Vendor strength",
                "Dependency to check", "Independent source", 0.81, PROPOSED, CANDIDATE, "editor", Instant.EPOCH);
        ReflectionTestUtils.setField(candidate, "id", 17L);
        candidate.bindInventoryEvidence("comparison-1", "fingerprint-1", retained);
        when(candidates.findById(17L)).thenReturn(Optional.of(candidate));
    }

    private ResultActions submit(long projectId, long candidateId, String body) throws Exception {
        return mvc.perform(patch("/api/projects/{projectId}/product-candidates/{candidateId}", projectId, candidateId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions upsert(String body) throws Exception {
        return mvc.perform(post("/api/projects/1/solutions/3/products")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void assertUnchanged() {
        verify(candidates, never()).save(any());
        assertThat(candidate.getReviewStatus()).isEqualTo(PROPOSED);
        assertThat(candidate.getSelectionStatus()).isEqualTo(CANDIDATE);
        assertThat(candidate.getUpdatedAt()).isEqualTo(Instant.EPOCH);
    }
}
