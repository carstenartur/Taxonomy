package com.taxonomy.portfolio.service;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
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
    private final TaxonomyNodeRepository taxonomyNodeRepository;
    private final ProjectPortfolioService projectService;

    public ProductCatalogService(ProductCatalogEntryRepository productRepository,
                                 ProductTaxonomyCoverageRepository coverageRepository,
                                 SolutionProductCandidateRepository candidateRepository,
                                 ProjectSolutionRepository projectSolutionRepository,
                                 TaxonomyNodeRepository taxonomyNodeRepository,
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
        if (request.verifiedAt() == null) {
            throw PortfolioException.validation("verifiedAt is required for product claims");
        }
        Instant now = Instant.now();
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
                request.costAmount(),
                normalizeCurrency(request.costCurrency()),
                ProjectPortfolioService.limited(request.costBasis(), 500, "costBasis"),
                source,
                request.verifiedAt(),
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
        ProductCatalogEntry product = requireProduct(productId, username, context);
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
                request.costAmount(),
                normalizeCurrency(request.costCurrency()),
                ProjectPortfolioService.limited(request.costBasis(), 500, "costBasis"),
                request.sourceReference() != null
                        ? ProjectPortfolioService.requireText(
                                request.sourceReference(), "sourceReference", 100_000) : null,
                request.verifiedAt(),
                Instant.now());
        return toProductView(product);
    }

    @Transactional
    public ProductView upsertTaxonomyCoverage(Long productId,
                                              UpsertTaxonomyCoverageRequest request,
                                              String username,
                                              WorkspaceContext context) {
        if (request == null) throw PortfolioException.validation("taxonomy coverage is required");
        ProductCatalogEntry product = requireProduct(productId, username, context);
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
        if (candidate.getId() != null) {
            candidate.update(
                    coverage,
                    exclusions,
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
        return new SolutionProductCandidateView(
                candidate.getId(),
                candidate.getProjectSolution().getId(),
                toProductView(candidate.getProduct()),
                candidate.getCoveragePercent(),
                candidate.getHardExclusions(),
                candidate.getStrengths(),
                candidate.getWeaknesses(),
                candidate.getOpenEvidence(),
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
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw PortfolioException.validation("costCurrency must be a three-letter ISO currency code");
        }
        return normalized;
    }
}
