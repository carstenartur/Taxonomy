package com.taxonomy.portfolio.service;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.MetaAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.ast.SourceLocation;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.portfolio.dto.PortfolioDtos.AddProjectSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProductRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateProductRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateProjectSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertProductCandidateRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertTaxonomyCoverageRequest;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.PortfolioTypes.ActionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.LifecycleStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.OperatingModel;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductSelectionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectSolutionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementSolutionRole;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.SolutionType;
import com.taxonomy.portfolio.model.ProductCatalogEntry;
import com.taxonomy.portfolio.model.ProductTaxonomyCoverage;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectSolution;
import com.taxonomy.portfolio.model.RequirementSolutionLink;
import com.taxonomy.portfolio.model.SolutionDefinition;
import com.taxonomy.portfolio.model.SolutionProductCandidate;
import com.taxonomy.portfolio.model.SolutionTaxonomyCoverage;
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProductCatalogEntryRepository;
import com.taxonomy.portfolio.repository.ProductTaxonomyCoverageRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectSolutionRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.portfolio.repository.RequirementSolutionLinkRepository;
import com.taxonomy.portfolio.repository.SolutionDefinitionRepository;
import com.taxonomy.portfolio.repository.SolutionProductCandidateRepository;
import com.taxonomy.portfolio.repository.SolutionTaxonomyCoverageRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Git contributor for durable solution and product decisions.
 *
 * <p>Every reference uses stable business keys. Database primary keys and
 * operational analysis jobs remain projections and are never used as merge
 * identities. Blocks are flat and independently mergeable so contributors can
 * add different solutions or products without touching the same text region.</p>
 */
@Service
public class PortfolioDecisionGitContributor {

    public static final String SOLUTION_BLOCK = "solutionDefinition";
    public static final String SOLUTION_TAXONOMY_BLOCK = "solutionTaxonomyCoverage";
    public static final String PRODUCT_BLOCK = "productDefinition";
    public static final String PRODUCT_TAXONOMY_BLOCK = "productTaxonomyCoverage";
    public static final String PROJECT_SOLUTION_BLOCK = "projectSolutionDecision";
    public static final String REQUIREMENT_SOLUTION_BLOCK = "requirementSolutionDecision";
    public static final String SOLUTION_PRODUCT_BLOCK = "solutionProductDecision";

    private static final Set<String> MANAGED_BLOCKS = Set.of(
            SOLUTION_BLOCK,
            SOLUTION_TAXONOMY_BLOCK,
            PRODUCT_BLOCK,
            PRODUCT_TAXONOMY_BLOCK,
            PROJECT_SOLUTION_BLOCK,
            REQUIREMENT_SOLUTION_BLOCK,
            SOLUTION_PRODUCT_BLOCK);
    private static final SourceLocation GENERATED =
            new SourceLocation("portfolio-decision-projection", 1, 1);

    private final ArchitectureProjectRepository projectRepository;
    private final ProjectRequirementRepository requirementRepository;
    private final SolutionDefinitionRepository solutionRepository;
    private final SolutionTaxonomyCoverageRepository solutionCoverageRepository;
    private final ProjectSolutionRepository projectSolutionRepository;
    private final RequirementSolutionLinkRepository requirementLinkRepository;
    private final ProductCatalogEntryRepository productRepository;
    private final ProductTaxonomyCoverageRepository productCoverageRepository;
    private final SolutionProductCandidateRepository candidateRepository;
    private final RequirementAnalysisSnapshotRepository snapshotRepository;
    private final SolutionPortfolioService solutionService;
    private final ProductCatalogService productService;
    private final PortfolioJsonCodec jsonCodec;
    private final TaxDslParser parser = new TaxDslParser();
    private final TaxDslSerializer serializer = new TaxDslSerializer();

    public PortfolioDecisionGitContributor(
            ArchitectureProjectRepository projectRepository,
            ProjectRequirementRepository requirementRepository,
            SolutionDefinitionRepository solutionRepository,
            SolutionTaxonomyCoverageRepository solutionCoverageRepository,
            ProjectSolutionRepository projectSolutionRepository,
            RequirementSolutionLinkRepository requirementLinkRepository,
            ProductCatalogEntryRepository productRepository,
            ProductTaxonomyCoverageRepository productCoverageRepository,
            SolutionProductCandidateRepository candidateRepository,
            RequirementAnalysisSnapshotRepository snapshotRepository,
            SolutionPortfolioService solutionService,
            ProductCatalogService productService,
            PortfolioJsonCodec jsonCodec) {
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.solutionRepository = solutionRepository;
        this.solutionCoverageRepository = solutionCoverageRepository;
        this.projectSolutionRepository = projectSolutionRepository;
        this.requirementLinkRepository = requirementLinkRepository;
        this.productRepository = productRepository;
        this.productCoverageRepository = productCoverageRepository;
        this.candidateRepository = candidateRepository;
        this.snapshotRepository = snapshotRepository;
        this.solutionService = solutionService;
        this.productService = productService;
        this.jsonCodec = jsonCodec;
    }

    /** Replace this contributor's blocks while preserving the architecture document. */
    @Transactional(readOnly = true)
    public String contributeTo(String dsl, String username, WorkspaceContext context) {
        DocumentAst document = parseOrEmpty(dsl);
        List<BlockAst> blocks = new ArrayList<>();
        for (BlockAst block : document.getBlocks()) {
            if (!MANAGED_BLOCKS.contains(block.getKind())) blocks.add(block);
        }
        blocks.addAll(exportBlocks(username, context));
        return serializer.serialize(new DocumentAst(document.getMeta(), blocks));
    }

    @Transactional(readOnly = true)
    public List<BlockAst> exportBlocks(String username, WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        List<BlockAst> blocks = new ArrayList<>();

        List<SolutionDefinition> solutions = solutionRepository
                .findByScopeKeyOrderByTitleAsc(scopeKey).stream()
                .sorted(Comparator.comparing(SolutionDefinition::getSolutionKey))
                .toList();
        for (SolutionDefinition solution : solutions) {
            blocks.add(block(SOLUTION_BLOCK, List.of(solution.getSolutionKey()), properties(
                    "title", solution.getTitle(),
                    "description", solution.getDescription(),
                    "solutionType", name(solution.getSolutionType()),
                    "operatingModel", name(solution.getOperatingModel()),
                    "lifecycleStatus", name(solution.getLifecycleStatus()),
                    "maturityLevel", string(solution.getMaturityLevel()),
                    "owner", solution.getOwnerUsername(),
                    "responsibleOrganization", solution.getResponsibleOrganization(),
                    "costAmount", string(solution.getCostAmount()),
                    "costCurrency", solution.getCostCurrency(),
                    "riskNotes", solution.getRiskNotes(),
                    "leadTimeDays", string(solution.getLeadTimeDays()),
                    "extensionAttributes", solution.getExtensionAttributesJson(),
                    "createdAt", string(solution.getCreatedAt()),
                    "updatedAt", string(solution.getUpdatedAt()),
                    PortfolioGitService.MANAGED_PROPERTY, "true")));
            for (SolutionTaxonomyCoverage coverage : solutionCoverageRepository
                    .findBySolutionIdOrderByNodeCodeAsc(solution.getId())) {
                blocks.add(block(SOLUTION_TAXONOMY_BLOCK,
                        List.of(solution.getSolutionKey(), coverage.getNodeCode()), properties(
                                "coveragePercent", string(coverage.getCoveragePercent()),
                                "evidence", coverage.getEvidence(),
                                "reviewStatus", name(coverage.getReviewStatus()),
                                "updatedBy", coverage.getUpdatedBy(),
                                "updatedAt", string(coverage.getUpdatedAt()),
                                PortfolioGitService.MANAGED_PROPERTY, "true")));
            }
        }

        List<ProductCatalogEntry> products = productRepository
                .findByScopeKeyOrderByManufacturerAscProductNameAsc(scopeKey).stream()
                .sorted(Comparator.comparing(ProductCatalogEntry::getProductKey))
                .toList();
        for (ProductCatalogEntry product : products) {
            blocks.add(block(PRODUCT_BLOCK, List.of(product.getProductKey()), properties(
                    "manufacturer", product.getManufacturer(),
                    "productFamily", product.getProductFamily(),
                    "productName", product.getProductName(),
                    "editionVersion", product.getEditionVersion(),
                    "productStatus", name(product.getProductStatus()),
                    "endOfSupport", string(product.getEndOfSupport()),
                    "licenseModel", product.getLicenseModel(),
                    "operatingModel", name(product.getOperatingModel()),
                    "supportedPlatforms", product.getSupportedPlatforms(),
                    "securityFeatures", product.getSecurityFeatures(),
                    "complianceFeatures", product.getComplianceFeatures(),
                    "costAmount", string(product.getCostAmount()),
                    "costCurrency", product.getCostCurrency(),
                    "costBasis", product.getCostBasis(),
                    "sourceReference", product.getSourceReference(),
                    "verifiedAt", string(product.getVerifiedAt()),
                    "createdBy", product.getCreatedBy(),
                    "createdAt", string(product.getCreatedAt()),
                    "updatedAt", string(product.getUpdatedAt()),
                    PortfolioGitService.MANAGED_PROPERTY, "true")));
            for (ProductTaxonomyCoverage coverage : productCoverageRepository
                    .findByProductIdOrderByNodeCodeAsc(product.getId())) {
                blocks.add(block(PRODUCT_TAXONOMY_BLOCK,
                        List.of(product.getProductKey(), coverage.getNodeCode()), properties(
                                "coveragePercent", string(coverage.getCoveragePercent()),
                                "evidence", coverage.getEvidence(),
                                "reviewStatus", name(coverage.getReviewStatus()),
                                "updatedBy", coverage.getUpdatedBy(),
                                "updatedAt", string(coverage.getUpdatedAt()),
                                PortfolioGitService.MANAGED_PROPERTY, "true")));
            }
        }

        for (ArchitectureProject project : projectRepository
                .findByScopeKeyOrderByUpdatedAtDesc(scopeKey).stream()
                .sorted(Comparator.comparing(ArchitectureProject::getProjectKey))
                .toList()) {
            for (ProjectSolution projectSolution : projectSolutionRepository
                    .findByProjectIdOrderByPriorityDescSolutionTitleAsc(project.getId())) {
                String solutionKey = projectSolution.getSolution().getSolutionKey();
                blocks.add(block(PROJECT_SOLUTION_BLOCK,
                        List.of(project.getProjectKey(), solutionKey), properties(
                                "status", name(projectSolution.getStatus()),
                                "actionStatus", name(projectSolution.getActionStatus()),
                                "priority", string(projectSolution.getPriority()),
                                "rationale", projectSolution.getRationale(),
                                "createdBy", projectSolution.getCreatedBy(),
                                "createdAt", string(projectSolution.getCreatedAt()),
                                "updatedAt", string(projectSolution.getUpdatedAt()),
                                PortfolioGitService.MANAGED_PROPERTY, "true")));

                for (RequirementSolutionLink link : requirementLinkRepository
                        .findByProjectSolutionIdOrderByRequirementRequirementKeyAsc(
                                projectSolution.getId())) {
                    blocks.add(block(REQUIREMENT_SOLUTION_BLOCK,
                            List.of(project.getProjectKey(),
                                    link.getRequirement().getRequirementKey(), solutionKey), properties(
                                    "snapshotId", link.getSnapshotId(),
                                    "coveragePercent", string(link.getCoveragePercent()),
                                    "role", name(link.getRole()),
                                    "reviewStatus", name(link.getReviewStatus()),
                                    "evidence", link.getEvidence(),
                                    "updatedBy", link.getUpdatedBy(),
                                    "updatedAt", string(link.getUpdatedAt()),
                                    PortfolioGitService.MANAGED_PROPERTY, "true")));
                }

                for (SolutionProductCandidate candidate : candidateRepository
                        .findByProjectSolutionIdOrderByCoveragePercentDesc(projectSolution.getId())) {
                    blocks.add(block(SOLUTION_PRODUCT_BLOCK,
                            List.of(project.getProjectKey(), solutionKey,
                                    candidate.getProduct().getProductKey()), properties(
                                    "coveragePercent", string(candidate.getCoveragePercent()),
                                    "hardExclusions", candidate.getHardExclusions(),
                                    "strengths", candidate.getStrengths(),
                                    "weaknesses", candidate.getWeaknesses(),
                                    "openEvidence", candidate.getOpenEvidence(),
                                    "confidence", string(candidate.getConfidence()),
                                    "reviewStatus", name(candidate.getReviewStatus()),
                                    "selectionStatus", name(candidate.getSelectionStatus()),
                                    "updatedBy", candidate.getUpdatedBy(),
                                    "updatedAt", string(candidate.getUpdatedAt()),
                                    PortfolioGitService.MANAGED_PROPERTY, "true")));
                }
            }
        }
        return List.copyOf(blocks);
    }

    /** Materialize solution and product decisions after projects and requirements exist. */
    @Transactional
    public DecisionMaterializeResult materialize(
            String dsl, String username, WorkspaceContext context) {
        DocumentAst document = parser.parse(dsl, "portfolio-decisions.taxdsl");
        List<String> warnings = new ArrayList<>();
        String scopeKey = PortfolioScope.key(username, context);
        Map<String, SolutionDefinition> solutions = new LinkedHashMap<>();
        Map<String, ProductCatalogEntry> products = new LinkedHashMap<>();
        Map<String, ArchitectureProject> projects = new LinkedHashMap<>();
        Map<String, ProjectSolution> projectSolutions = new LinkedHashMap<>();

        int solutionCount = materializeSolutions(
                document, username, context, scopeKey, solutions, warnings);
        int productCount = materializeProducts(
                document, username, context, scopeKey, products, warnings);
        int projectSolutionCount = materializeProjectSolutions(
                document, username, context, scopeKey,
                solutions, projects, projectSolutions, warnings);
        int requirementLinkCount = materializeRequirementLinks(
                document, username, context, projects, projectSolutions, warnings);
        int candidateCount = materializeCandidates(
                document, username, context, products, projectSolutions, warnings);

        return new DecisionMaterializeResult(
                solutionCount,
                productCount,
                projectSolutionCount,
                requirementLinkCount,
                candidateCount,
                List.copyOf(warnings));
    }

    private int materializeSolutions(DocumentAst document,
                                     String username,
                                     WorkspaceContext context,
                                     String scopeKey,
                                     Map<String, SolutionDefinition> solutions,
                                     List<String> warnings) {
        int count = 0;
        for (BlockAst block : document.blocksOfKind(SOLUTION_BLOCK)) {
            String key = header(block, 0, warnings);
            if (key == null) continue;
            try {
                SolutionDefinition existing = solutionRepository
                        .findByScopeKeyAndSolutionKeyIgnoreCase(scopeKey, key).orElse(null);
                if (existing == null) {
                    solutionService.createSolution(new CreateSolutionRequest(
                            key,
                            required(block, "title", key),
                            block.property("description"),
                            enumValue(SolutionType.class, block.property("solutionType"), SolutionType.OTHER),
                            enumValue(OperatingModel.class, block.property("operatingModel"), OperatingModel.UNSPECIFIED),
                            enumValue(LifecycleStatus.class, block.property("lifecycleStatus"), LifecycleStatus.PLANNED),
                            integer(block.property("maturityLevel"), 0),
                            block.property("responsibleOrganization"),
                            decimal(block.property("costAmount")),
                            block.property("costCurrency"),
                            block.property("riskNotes"),
                            nullableInteger(block.property("leadTimeDays")),
                            jsonCodec.readStringMap(block.property("extensionAttributes"))),
                            username, context);
                    count++;
                } else {
                    solutionService.updateSolution(existing.getId(), new UpdateSolutionRequest(
                            required(block, "title", existing.getTitle()),
                            block.property("description"),
                            enumValue(SolutionType.class, block.property("solutionType"), existing.getSolutionType()),
                            enumValue(OperatingModel.class, block.property("operatingModel"), existing.getOperatingModel()),
                            enumValue(LifecycleStatus.class, block.property("lifecycleStatus"), existing.getLifecycleStatus()),
                            integer(block.property("maturityLevel"), existing.getMaturityLevel()),
                            valueOrDefault(block.property("owner"), existing.getOwnerUsername()),
                            block.property("responsibleOrganization"),
                            decimal(block.property("costAmount")),
                            block.property("costCurrency"),
                            block.property("riskNotes"),
                            nullableInteger(block.property("leadTimeDays")),
                            jsonCodec.readStringMap(block.property("extensionAttributes"))),
                            username, context);
                }
                SolutionDefinition materialized = solutionRepository
                        .findByScopeKeyAndSolutionKeyIgnoreCase(scopeKey, key).orElse(null);
                if (materialized != null) solutions.put(key, materialized);
            } catch (RuntimeException exception) {
                warnings.add(identity(block) + ": " + exception.getMessage());
            }
        }

        for (BlockAst block : document.blocksOfKind(SOLUTION_TAXONOMY_BLOCK)) {
            String solutionKey = header(block, 0, warnings);
            String nodeCode = header(block, 1, warnings);
            SolutionDefinition solution = solutions.get(solutionKey);
            if (solution == null || nodeCode == null) {
                warnings.add(identity(block) + " references an unavailable solution or node");
                continue;
            }
            try {
                solutionService.upsertTaxonomyCoverage(solution.getId(),
                        new UpsertTaxonomyCoverageRequest(
                                nodeCode,
                                integer(block.property("coveragePercent"), 0),
                                block.property("evidence"),
                                enumValue(ReviewStatus.class, block.property("reviewStatus"), ReviewStatus.PROPOSED)),
                        username, context);
            } catch (RuntimeException exception) {
                warnings.add(identity(block) + ": " + exception.getMessage());
            }
        }
        return count;
    }

    private int materializeProducts(DocumentAst document,
                                    String username,
                                    WorkspaceContext context,
                                    String scopeKey,
                                    Map<String, ProductCatalogEntry> products,
                                    List<String> warnings) {
        int count = 0;
        for (BlockAst block : document.blocksOfKind(PRODUCT_BLOCK)) {
            String key = header(block, 0, warnings);
            if (key == null) continue;
            Instant verifiedAt = instant(block.property("verifiedAt"));
            if (verifiedAt == null || isBlank(block.property("sourceReference"))) {
                warnings.add(identity(block) + " requires sourceReference and verifiedAt");
                continue;
            }
            try {
                ProductCatalogEntry existing = productRepository
                        .findByScopeKeyAndProductKeyIgnoreCase(scopeKey, key).orElse(null);
                if (existing == null) {
                    productService.createProduct(new CreateProductRequest(
                            key,
                            required(block, "manufacturer", "Unknown"),
                            block.property("productFamily"),
                            required(block, "productName", key),
                            block.property("editionVersion"),
                            enumValue(ProductStatus.class, block.property("productStatus"), ProductStatus.CANDIDATE),
                            date(block.property("endOfSupport")),
                            block.property("licenseModel"),
                            enumValue(OperatingModel.class, block.property("operatingModel"), OperatingModel.UNSPECIFIED),
                            block.property("supportedPlatforms"),
                            block.property("securityFeatures"),
                            block.property("complianceFeatures"),
                            decimal(block.property("costAmount")),
                            block.property("costCurrency"),
                            block.property("costBasis"),
                            block.property("sourceReference"),
                            verifiedAt), username, context);
                    count++;
                } else {
                    productService.updateProduct(existing.getId(), new UpdateProductRequest(
                            required(block, "manufacturer", existing.getManufacturer()),
                            block.property("productFamily"),
                            required(block, "productName", existing.getProductName()),
                            block.property("editionVersion"),
                            enumValue(ProductStatus.class, block.property("productStatus"), existing.getProductStatus()),
                            date(block.property("endOfSupport")),
                            block.property("licenseModel"),
                            enumValue(OperatingModel.class, block.property("operatingModel"), existing.getOperatingModel()),
                            block.property("supportedPlatforms"),
                            block.property("securityFeatures"),
                            block.property("complianceFeatures"),
                            decimal(block.property("costAmount")),
                            block.property("costCurrency"),
                            block.property("costBasis"),
                            block.property("sourceReference"),
                            verifiedAt), username, context);
                }
                ProductCatalogEntry materialized = productRepository
                        .findByScopeKeyAndProductKeyIgnoreCase(scopeKey, key).orElse(null);
                if (materialized != null) products.put(key, materialized);
            } catch (RuntimeException exception) {
                warnings.add(identity(block) + ": " + exception.getMessage());
            }
        }

        for (BlockAst block : document.blocksOfKind(PRODUCT_TAXONOMY_BLOCK)) {
            String productKey = header(block, 0, warnings);
            String nodeCode = header(block, 1, warnings);
            ProductCatalogEntry product = products.get(productKey);
            if (product == null || nodeCode == null) {
                warnings.add(identity(block) + " references an unavailable product or node");
                continue;
            }
            try {
                productService.upsertTaxonomyCoverage(product.getId(),
                        new UpsertTaxonomyCoverageRequest(
                                nodeCode,
                                integer(block.property("coveragePercent"), 0),
                                block.property("evidence"),
                                enumValue(ReviewStatus.class, block.property("reviewStatus"), ReviewStatus.PROPOSED)),
                        username, context);
            } catch (RuntimeException exception) {
                warnings.add(identity(block) + ": " + exception.getMessage());
            }
        }
        return count;
    }

    private int materializeProjectSolutions(
            DocumentAst document,
            String username,
            WorkspaceContext context,
            String scopeKey,
            Map<String, SolutionDefinition> solutions,
            Map<String, ArchitectureProject> projects,
            Map<String, ProjectSolution> projectSolutions,
            List<String> warnings) {
        for (ArchitectureProject project : projectRepository
                .findByScopeKeyOrderByUpdatedAtDesc(scopeKey)) {
            projects.put(project.getProjectKey(), project);
        }

        int count = 0;
        for (BlockAst block : document.blocksOfKind(PROJECT_SOLUTION_BLOCK)) {
            String projectKey = header(block, 0, warnings);
            String solutionKey = header(block, 1, warnings);
            ArchitectureProject project = projects.get(projectKey);
            SolutionDefinition solution = solutions.get(solutionKey);
            if (project == null || solution == null) {
                warnings.add(identity(block) + " references an unavailable project or solution");
                continue;
            }
            try {
                ProjectSolution existing = projectSolutionRepository
                        .findByProjectIdAndSolutionId(project.getId(), solution.getId()).orElse(null);
                if (existing == null) {
                    solutionService.addProjectSolution(project.getId(),
                            new AddProjectSolutionRequest(
                                    solution.getId(),
                                    enumValue(ProjectSolutionStatus.class, block.property("status"), ProjectSolutionStatus.PROPOSED),
                                    enumValue(ActionStatus.class, block.property("actionStatus"), ActionStatus.UNDECIDED),
                                    integer(block.property("priority"), 50),
                                    block.property("rationale")),
                            username, context);
                    count++;
                } else {
                    solutionService.updateProjectSolution(project.getId(), existing.getId(),
                            new UpdateProjectSolutionRequest(
                                    enumValue(ProjectSolutionStatus.class, block.property("status"), existing.getStatus()),
                                    enumValue(ActionStatus.class, block.property("actionStatus"), existing.getActionStatus()),
                                    integer(block.property("priority"), existing.getPriority()),
                                    block.property("rationale")),
                            username, context);
                }
                ProjectSolution materialized = projectSolutionRepository
                        .findByProjectIdAndSolutionId(project.getId(), solution.getId()).orElse(null);
                if (materialized != null) {
                    projectSolutions.put(composite(projectKey, solutionKey), materialized);
                }
            } catch (RuntimeException exception) {
                warnings.add(identity(block) + ": " + exception.getMessage());
            }
        }
        return count;
    }

    private int materializeRequirementLinks(
            DocumentAst document,
            String username,
            WorkspaceContext context,
            Map<String, ArchitectureProject> projects,
            Map<String, ProjectSolution> projectSolutions,
            List<String> warnings) {
        int count = 0;
        for (BlockAst block : document.blocksOfKind(REQUIREMENT_SOLUTION_BLOCK)) {
            String projectKey = header(block, 0, warnings);
            String requirementKey = header(block, 1, warnings);
            String solutionKey = header(block, 2, warnings);
            ArchitectureProject project = projects.get(projectKey);
            ProjectSolution projectSolution = projectSolutions.get(composite(projectKey, solutionKey));
            if (project == null || projectSolution == null || requirementKey == null) {
                warnings.add(identity(block) + " references unavailable project data");
                continue;
            }
            ProjectRequirement requirement = requirementRepository
                    .findByProjectIdAndRequirementKeyIgnoreCase(project.getId(), requirementKey)
                    .orElse(null);
            if (requirement == null) {
                warnings.add(identity(block) + " references an unavailable requirement");
                continue;
            }
            String snapshotId = block.property("snapshotId");
            if (!isBlank(snapshotId)
                    && snapshotRepository.findByIdAndProjectId(snapshotId, project.getId()).isEmpty()) {
                // The source snapshot remains in Git provenance, but a target
                // projection must not point at another workspace's snapshot.
                snapshotId = null;
            }
            try {
                RequirementSolutionLink link = requirementLinkRepository
                        .findByProjectSolutionIdAndRequirementId(
                                projectSolution.getId(), requirement.getId())
                        .orElseGet(() -> new RequirementSolutionLink(
                                projectSolution,
                                requirement,
                                snapshotId,
                                integer(block.property("coveragePercent"), 0),
                                enumValue(RequirementSolutionRole.class, block.property("role"), RequirementSolutionRole.USES),
                                enumValue(ReviewStatus.class, block.property("reviewStatus"), ReviewStatus.PROPOSED),
                                block.property("evidence"),
                                valueOrDefault(block.property("updatedBy"), username),
                                valueOrDefault(instant(block.property("updatedAt")), Instant.now())));
                if (link.getId() != null) {
                    link.update(
                            snapshotId,
                            integer(block.property("coveragePercent"), link.getCoveragePercent()),
                            enumValue(RequirementSolutionRole.class, block.property("role"), link.getRole()),
                            enumValue(ReviewStatus.class, block.property("reviewStatus"), link.getReviewStatus()),
                            block.property("evidence"),
                            valueOrDefault(block.property("updatedBy"), username),
                            valueOrDefault(instant(block.property("updatedAt")), Instant.now()));
                } else {
                    count++;
                }
                requirementLinkRepository.save(link);
            } catch (RuntimeException exception) {
                warnings.add(identity(block) + ": " + exception.getMessage());
            }
        }
        return count;
    }

    private int materializeCandidates(
            DocumentAst document,
            String username,
            WorkspaceContext context,
            Map<String, ProductCatalogEntry> products,
            Map<String, ProjectSolution> projectSolutions,
            List<String> warnings) {
        int count = 0;
        for (BlockAst block : document.blocksOfKind(SOLUTION_PRODUCT_BLOCK)) {
            String projectKey = header(block, 0, warnings);
            String solutionKey = header(block, 1, warnings);
            String productKey = header(block, 2, warnings);
            ProjectSolution projectSolution = projectSolutions.get(composite(projectKey, solutionKey));
            ProductCatalogEntry product = products.get(productKey);
            if (projectSolution == null || product == null) {
                warnings.add(identity(block) + " references unavailable solution or product data");
                continue;
            }
            try {
                boolean existed = candidateRepository
                        .findByProjectSolutionIdAndProductId(
                                projectSolution.getId(), product.getId()).isPresent();
                productService.upsertCandidate(
                        projectSolution.getProject().getId(),
                        projectSolution.getId(),
                        new UpsertProductCandidateRequest(
                                product.getId(),
                                integer(block.property("coveragePercent"), 0),
                                block.property("hardExclusions"),
                                block.property("strengths"),
                                block.property("weaknesses"),
                                block.property("openEvidence"),
                                decimalDouble(block.property("confidence"), 0.0),
                                enumValue(ReviewStatus.class, block.property("reviewStatus"), ReviewStatus.PROPOSED),
                                enumValue(ProductSelectionStatus.class, block.property("selectionStatus"), ProductSelectionStatus.CANDIDATE)),
                        username, context);
                if (!existed) count++;
            } catch (RuntimeException exception) {
                warnings.add(identity(block) + ": " + exception.getMessage());
            }
        }
        return count;
    }

    private DocumentAst parseOrEmpty(String dsl) {
        if (dsl == null || dsl.isBlank()) {
            return new DocumentAst(
                    new MetaAst(MetaAst.LANGUAGE_ID, MetaAst.CURRENT_VERSION,
                            "default", GENERATED),
                    List.of());
        }
        return parser.parse(dsl, "architecture.taxdsl");
    }

    private static BlockAst block(String kind, List<String> header, List<PropertyAst> properties) {
        return new BlockAst(kind, header, properties, List.of(), Map.of(), GENERATED);
    }

    private static List<PropertyAst> properties(String... pairs) {
        List<PropertyAst> result = new ArrayList<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            if (pairs[index + 1] != null) {
                result.add(new PropertyAst(pairs[index], pairs[index + 1], GENERATED));
            }
        }
        return result;
    }

    private static String header(BlockAst block, int index, List<String> warnings) {
        if (block.getHeaderTokens().size() <= index) {
            warnings.add("Invalid header for " + identity(block));
            return null;
        }
        return block.getHeaderTokens().get(index);
    }

    private static String required(BlockAst block, String property, String fallback) {
        String value = block.property(property);
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String identity(BlockAst block) {
        return block.getKind() + " " + String.join(" ", block.getHeaderTokens());
    }

    private static String composite(String left, String right) {
        return left + '\u0000' + right;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static int integer(String value, int fallback) {
        Integer parsed = nullableInteger(value);
        return parsed != null ? parsed : fallback;
    }

    private static Integer nullableInteger(String value) {
        if (isBlank(value)) return null;
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static BigDecimal decimal(String value) {
        if (isBlank(value)) return null;
        try {
            return new BigDecimal(value.strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static double decimalDouble(String value, double fallback) {
        if (isBlank(value)) return fallback;
        try {
            return Double.parseDouble(value.strip());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static LocalDate date(String value) {
        if (isBlank(value)) return null;
        try {
            return LocalDate.parse(value.strip());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Instant instant(String value) {
        if (isBlank(value)) return null;
        try {
            return Instant.parse(value.strip());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static <T> T valueOrDefault(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type, String value, E fallback) {
        if (isBlank(value)) return fallback;
        try {
            return Enum.valueOf(type, value.strip().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    public record DecisionMaterializeResult(
            int solutionsCreated,
            int productsCreated,
            int projectSolutionsCreated,
            int requirementLinksCreated,
            int productCandidatesCreated,
            List<String> warnings) {
        public DecisionMaterializeResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
