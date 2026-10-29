package com.taxonomy.portfolio.service;

import com.taxonomy.catalog.api.TaxonomyNodeLookup;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProductRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProductView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SolutionProductCandidateView;
import com.taxonomy.portfolio.dto.PortfolioDtos.TaxonomyCoverageView;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateProductRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertProductCandidateRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertTaxonomyCoverageRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.OperatingModel;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductSelectionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.ProductCatalogEntry;
import com.taxonomy.portfolio.model.ProductTaxonomyCoverage;
import com.taxonomy.portfolio.model.ProjectSolution;
import com.taxonomy.portfolio.model.SolutionProductCandidate;
import com.taxonomy.portfolio.repository.ProductCatalogEntryRepository;
import com.taxonomy.portfolio.repository.ProductTaxonomyCoverageRepository;
import com.taxonomy.portfolio.repository.ProjectSolutionRepository;
import com.taxonomy.portfolio.repository.SolutionProductCandidateRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Manually curated, sourced product catalogue and human-reviewed solution candidates. */
@Service
public class ProductCatalogService {

    private static final Pattern BUSINESS_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final ProductCatalogEntryRepository productRepository;
    private final ProductTaxonomyCoverageRepository coverageRepository;
    private final SolutionProductCandidateRepository candidateRepository;
    private final ProjectSolutionRepository projectSolutionRepository;
    private final TaxonomyNodeLookup taxonomyNodeRepository;
    private final ProjectPortfolioService projectService;

    public ProductCatalogService(ProductCatalogEntryRepository productRepository,
                                 ProductTaxonomyCoverageRepository coverageRepository,
                                 SolutionProductCandidateRepository candidateRepository,
                                 ProjectSolutionRepository projectSolutionRepository,
                                 TaxonomyNodeLookup taxonomyNodeRepository,
                                 ProjectPortfolioService projectService) {
        this.productRepository = productRepository;
        this.coverageRepository = coverageRepository;
        this.candidateRepository = candidateRepository;
        this.projectSolutionRepository = projectSolutionRepository;
        this.taxonomyNodeRepository = taxonomyNodeRepository;
        this.projectService = projectService;
    }

    @Transactional
    public ProductView createProduct(CreateProductRequest request,
                                     String username,
                                     WorkspaceContext context) {
        if (request == null) throw PortfolioException.validation("product request is required");
        String scopeKey = PortfolioScope.key(username, context);
        String productKey = normalizeKey(request.productKey(), "productKey");
        if (productRepository.findByScopeKeyAndProductKeyIgnoreCase(scopeKey, productKey).isPresent()) {
            throw PortfolioException.conflict("Product key already exists in this workspace: " + productKey);
        }
        String source = ProjectPortfolioService.requireText(
                request.sourceReference(), "sourceReference", 100_000);
        Instant now = Instant.now();
        var costAmount = PortfolioValueValidator.money(request.costAmount(), "costAmount");
        String costCurrency = normalizeCurrency(request.costCurrency());
        PortfolioValueValidator.requireMoneyPair(
                costAmount, costCurrency, "costAmount", "costCurrency");
        Instant verifiedAt = PortfolioValueValidator.verifiedAt(request.verifiedAt(), now, true);

        ProductCatalogEntry product = new ProductCatalogEntry(
                scopeKey,
                PortfolioScope.workspaceId(context),
                productKey,
                ProjectPortfolioService.requireText(request.manufacturer(), "manufacturer", 240),
                ProjectPortfolioService.limited(request.productFamily(), 240, "productFamily"),
                ProjectPortfolioService.requireText(request.productName(), "productName", 240),
                ProjectPortfolioService.limited(request.editionVersion(), 160, "editionVersion"),
                request.productStatus() != null ? request.productStatus() : ProductStatus.CANDIDATE,
                request.endOfSupport(),
                ProjectPortfolioService.limited(request.licenseModel(), 500, "licenseModel"),
                request.operatingModel() != null ? request.operatingModel() : OperatingModel.UNSPECIFIED,
                ProjectPortfolioService.limited(request.supportedPlatforms(), 2000, "supportedPlatforms"),
                ProjectPortfolioService.limited(request.securityFeatures(), 4000, "securityFeatures"),
                ProjectPortfolioService.limited(request.complianceFeatures(), 4000, "complianceFeatures"),
                costAmount,
                costCurrency,
                ProjectPortfolioService.limited(request.costBasis(), 500, "costBasis"),
                source,
                verifiedAt,
                PortfolioScope.username(username, context),
                now);
        return toProductView(productRepository.save(product));
    }

    @Transactional(readOnly = true)
    public List<ProductView> listProducts(String username, WorkspaceContext context) {
        return productRepository
                .findByScopeKeyOrderByManufacturerAscProductNameAsc(PortfolioScope.key(username, context))
                .stream().map(this::toProductView).toList();
    }

    @Transactional(readOnly = true)
    public ProductView getProduct(Long productId, String username, WorkspaceContext context) {
        return toProductView(requireProduct(productId, username, context));
    }

    @Transactional
    public ProductView updateProduct(Long productId,
                                     UpdateProductRequest request,
                                     String username,
                                     WorkspaceContext context) {
        if (request == null) throw PortfolioException.validation("product update is required");
        ProductCatalogEntry product = requireProductForUpdate(productId, username, context);
        Instant now = Instant.now();
        var requestedAmount = request.costAmount() != null
                ? PortfolioValueValidator.money(request.costAmount(), "costAmount") : null;
        String requestedCurrency = request.costCurrency() != null
                ? normalizeCurrency(request.costCurrency()) : null;
        var effectiveAmount = request.costAmount() != null
                ? requestedAmount : product.getCostAmount();
        String effectiveCurrency = request.costCurrency() != null
                ? requestedCurrency : product.getCostCurrency();
        PortfolioValueValidator.requireMoneyPair(
                effectiveAmount, effectiveCurrency, "costAmount", "costCurrency");
        Instant verifiedAt = request.verifiedAt() != null
                ? PortfolioValueValidator.verifiedAt(request.verifiedAt(), now, false) : null;

        product.update(
                request.manufacturer() != null
                        ? ProjectPortfolioService.requireText(request.manufacturer(), "manufacturer", 240) : null,
                ProjectPortfolioService.limited(request.productFamily(), 240, "productFamily"),
                request.productName() != null
                        ? ProjectPortfolioService.requireText(request.productName(), "productName", 240) : null,
                ProjectPortfolioService.limited(request.editionVersion(), 160, "editionVersion"),
                request.productStatus(),
                request.endOfSupport(),
                ProjectPortfolioService.limited(request.licenseModel(), 500, "licenseModel"),
                request.operatingModel(),
                ProjectPortfolioService.limited(request.supportedPlatforms(), 2000, "supportedPlatforms"),
                ProjectPortfolioService.limited(request.securityFeatures(), 4000, "securityFeatures"),
                ProjectPortfolioService.limited(request.complianceFeatures(), 4000, "complianceFeatures"),
                requestedAmount,
                requestedCurrency,
                ProjectPortfolioService.limited(request.costBasis(), 500, "costBasis"),
                request.sourceReference() != null
                        ? ProjectPortfolioService.requireText(
                                request.sourceReference(), "sourceReference", 100_000) : null,
                verifiedAt,
                now);
        return toProductView(product);
    }

    @Transactional
    public ProductView upsertTaxonomyCoverage(Long productId,
                                              UpsertTaxonomyCoverageRequest request,
                                              String username,
                                              WorkspaceContext context) {
        if (request == null) throw PortfolioException.validation("taxonomy coverage is required");
        ProductCatalogEntry product = requireProductForUpdate(productId, username, context);
        String nodeCode = requireNodeCode(request.nodeCode());
        int coverage = normalizePercentage(request.coveragePercent(), "coveragePercent");
        Instant now = Instant.now();
        ProductTaxonomyCoverage mapping = coverageRepository
                .findByProductIdAndNodeCode(productId, nodeCode)
                .orElseGet(() -> new ProductTaxonomyCoverage(
                        product,
                        nodeCode,
                        coverage,
                        ProjectPortfolioService.limited(request.evidence(), 2000, "evidence"),
                        request.reviewStatus() != null ? request.reviewStatus() : ReviewStatus.PROPOSED,
                        PortfolioScope.username(username, context),
                        now));
        if (mapping.getId() != null) {
            mapping.update(
                    coverage,
                    ProjectPortfolioService.limited(request.evidence(), 2000, "evidence"),
                    request.reviewStatus(),
                    PortfolioScope.username(username, context),
                    now);
        }
        coverageRepository.save(mapping);
        return toProductView(product);
    }

    @Transactional
    public SolutionProductCandidateView upsertCandidate(Long projectId,
                                                        Long projectSolutionId,
                                                        UpsertProductCandidateRequest request,
                                                        String username,
                                                        WorkspaceContext context) {
        if (request == null) throw PortfolioException.validation("product candidate is required");
        projectService.requireProject(projectId, username, context);
        ProjectSolution projectSolution = projectSolutionRepository
                .findByIdAndProjectId(projectSolutionId, projectId)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Project solution not found: " + projectSolutionId));
        ProductCatalogEntry product = requireProduct(request.productId(), username, context);
        int coverage = normalizePercentage(request.coveragePercent(), "coveragePercent");
        double confidence = normalizeConfidence(request.confidence());
        ReviewStatus reviewStatus = request.reviewStatus() != null
                ? request.reviewStatus() : ReviewStatus.PROPOSED;
        ProductSelectionStatus selectionStatus = request.selectionStatus() != null
                ? request.selectionStatus() : ProductSelectionStatus.CANDIDATE;
        String exclusions = ProjectPortfolioService.limited(
                request.hardExclusions(), 4000, "hardExclusions");
        if (selectionStatus == ProductSelectionStatus.SELECTED) {
            if (reviewStatus != ReviewStatus.CONFIRMED) {
                throw PortfolioException.validation(
                        "A selected product candidate must have reviewStatus CONFIRMED");
            }
            if (exclusions != null && !exclusions.isBlank()) {
                throw PortfolioException.validation(
                        "A product with hard exclusions cannot be selected");
            }
        }

        Instant now = Instant.now();
        SolutionProductCandidate candidate = candidateRepository
                .findByProjectSolutionIdAndProductId(projectSolutionId, product.getId())
                .orElseGet(() -> new SolutionProductCandidate(
                        projectSolution,
                        product,
                        coverage,
                        exclusions,
                        ProjectPortfolioService.limited(request.strengths(), 4000, "strengths"),
                        ProjectPortfolioService.limited(request.weaknesses(), 4000, "weaknesses"),
                        ProjectPortfolioService.limited(request.openEvidence(), 4000, "openEvidence"),
                        confidence,
                        reviewStatus,
                        selectionStatus,
                        PortfolioScope.username(username, context),
                        now));
        if (candidate.getInventoryHardExclusions() != null && !candidate.getInventoryHardExclusions().isBlank()) {
            if (selectionStatus == ProductSelectionStatus.SELECTED) throw PortfolioException.validation("Retained inventory hard exclusions prevent selection");
        }
        String effectiveExclusions = candidate.getInventoryHardExclusions() != null && !candidate.getInventoryHardExclusions().isBlank()
                ? candidate.getInventoryHardExclusions() : exclusions;
        if (candidate.getId() != null) {
            candidate.update(
                    coverage,
                    effectiveExclusions,
                    ProjectPortfolioService.limited(request.strengths(), 4000, "strengths"),
                    ProjectPortfolioService.limited(request.weaknesses(), 4000, "weaknesses"),
                    ProjectPortfolioService.limited(request.openEvidence(), 4000, "openEvidence"),
                    confidence,
                    reviewStatus,
                    selectionStatus,
                    PortfolioScope.username(username, context),
                    now);
        }
        candidateRepository.save(candidate);
        return toCandidateView(candidate);
    }

    /** Only the deterministic inventory workflow replaces the previous context's retained findings. */
    @Transactional
    public SolutionProductCandidateView applyReviewedInventoryCandidate(Long projectId,Long projectSolutionId,
            UpsertProductCandidateRequest request,String comparisonId,String fingerprint,String computedHardExclusions,
            String username,WorkspaceContext context) {
        projectService.requireProject(projectId,username,context);
        requireProduct(request.productId(),username,context);
        candidateRepository.findByProjectSolutionIdAndProductId(projectSolutionId,request.productId())
                .ifPresent(candidate->candidate.bindInventoryEvidence(comparisonId,fingerprint,computedHardExclusions));
        var result=upsertCandidate(projectId,projectSolutionId,request,username,context);
        bindInventoryEvidence(result.id(),comparisonId,fingerprint,computedHardExclusions,username,context);
        return result;
    }

    /** Bind retained findings independently of browser-editable candidate fields. */
    private void bindInventoryEvidence(Long candidateId,String comparisonId,String fingerprint,String hardExclusions,
                                      String username,WorkspaceContext context) {
        var candidate=candidateRepository.findById(candidateId).orElseThrow(()->PortfolioException.notFound("Product candidate not found"));
        requireProduct(candidate.getProduct().getId(),username,context);
        projectService.requireProject(candidate.getProjectSolution().getProject().getId(),username,context);
        candidate.bindInventoryEvidence(comparisonId,fingerprint,hardExclusions);
    }

    @Transactional(readOnly = true)
    public List<SolutionProductCandidateView> listCandidates(Long projectId,
                                                             Long projectSolutionId,
                                                             String username,
                                                             WorkspaceContext context) {
        projectService.requireProject(projectId, username, context);
        projectSolutionRepository.findByIdAndProjectId(projectSolutionId, projectId)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Project solution not found: " + projectSolutionId));
        return candidateRepository.findByProjectSolutionIdOrderByCoveragePercentDesc(projectSolutionId)
                .stream().map(this::toCandidateView).toList();
    }

    @Transactional(readOnly = true)
    public ProductCatalogEntry requireProduct(Long productId,
                                              String username,
                                              WorkspaceContext context) {
        if (productId == null) throw PortfolioException.validation("productId is required");
        return productRepository.findByIdAndScopeKey(productId, PortfolioScope.key(username, context))
                .orElseThrow(() -> PortfolioException.notFound("Product not found: " + productId));
    }

    /** Shared input fence for catalogue fields, confirmed coverage and immutable inventory assertions.
     * Reviewed application acquires the project aggregate first, then this product lock,
     * and retains both until its journal/application transaction commits. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ProductCatalogEntry requireProductForUpdate(Long productId, String username, WorkspaceContext context) {
        if(productId==null)throw PortfolioException.validation("productId is required");
        try {
            return productRepository.findByIdAndScopeKeyForUpdate(productId,PortfolioScope.key(username,context))
                    .orElseThrow(()->PortfolioException.notFound("Product not found: "+productId));
        } catch(jakarta.persistence.OptimisticLockException | org.springframework.dao.OptimisticLockingFailureException concurrent) {
            throw PortfolioException.conflict("Product inputs changed concurrently; retry the operation against current evidence");
        }
    }

    public ProductView toProductView(ProductCatalogEntry product) {
        List<TaxonomyCoverageView> coverage = coverageRepository
                .findByProductIdOrderByNodeCodeAsc(product.getId()).stream()
                .map(mapping -> new TaxonomyCoverageView(
                        mapping.getId(),
                        mapping.getNodeCode(),
                        mapping.getCoveragePercent(),
                        mapping.getEvidence(),
                        mapping.getReviewStatus(),
                        mapping.getUpdatedBy(),
                        mapping.getUpdatedAt()))
                .toList();
        return new ProductView(
                product.getId(),
                product.getProductKey(),
                product.getManufacturer(),
                product.getProductFamily(),
                product.getProductName(),
                product.getEditionVersion(),
                product.getProductStatus(),
                product.getEndOfSupport(),
                product.getLicenseModel(),
                product.getOperatingModel(),
                product.getSupportedPlatforms(),
                product.getSecurityFeatures(),
                product.getComplianceFeatures(),
                product.getCostAmount(),
                product.getCostCurrency(),
                product.getCostBasis(),
                product.getSourceReference(),
                product.getVerifiedAt(),
                product.getCreatedBy(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                coverage);
    }

    public SolutionProductCandidateView toCandidateView(SolutionProductCandidate candidate) {
        String openEvidence=candidate.getOpenEvidence();
        if(candidate.getInventoryComparisonId()!=null && (openEvidence==null || !openEvidence.contains(candidate.getInventoryComparisonId())))
            openEvidence=java.util.Objects.toString(openEvidence,"")+"; Inventory comparison "+candidate.getInventoryComparisonId()+" / "+candidate.getInventoryFingerprint();
        return new SolutionProductCandidateView(
                candidate.getId(),
                candidate.getProjectSolution().getId(),
                toProductView(candidate.getProduct()),
                candidate.getCoveragePercent(),
                candidate.getHardExclusions(),
                candidate.getStrengths(),
                candidate.getWeaknesses(),
                openEvidence,
                candidate.getConfidence(),
                candidate.getReviewStatus(),
                candidate.getSelectionStatus(),
                candidate.getUpdatedBy(),
                candidate.getUpdatedAt());
    }

    private String requireNodeCode(String nodeCode) {
        String normalized = ProjectPortfolioService.requireText(nodeCode, "nodeCode", 80)
                .toUpperCase(Locale.ROOT);
        if (taxonomyNodeRepository.findByCode(normalized).isEmpty()) {
            throw PortfolioException.validation("Unknown taxonomy node: " + normalized);
        }
        return normalized;
    }

    private static String normalizeKey(String value, String field) {
        String normalized = ProjectPortfolioService.requireText(value, field, 64)
                .toUpperCase(Locale.ROOT);
        if (!BUSINESS_KEY.matcher(normalized).matches()) {
            throw PortfolioException.validation(
                    field + " contains unsupported characters");
        }
        return normalized;
    }

    private static int normalizePercentage(int value, String field) {
        if (value < 0 || value > 100) {
            throw PortfolioException.validation(field + " must be between 0 and 100");
        }
        return value;
    }

    private static double normalizeConfidence(double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw PortfolioException.validation("confidence must be between 0.0 and 1.0");
        }
        return value;
    }

    private static String normalizeCurrency(String value) {
        return PortfolioValueValidator.currency(value, "costCurrency");
    }
}
