package com.taxonomy.portfolio.service;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.MetaAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.ast.SourceLocation;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
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
 * Projects durable solution and product decisions into Git and materializes
 * them into another workspace using stable business keys.
 *
 * <p>No database primary key is part of a block identity. Project, requirement,
 * solution and product links therefore remain portable across workspaces and
 * deployments. Operational jobs and large analysis payloads stay relational.</p>
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
            SolutionProductCandidateRepository candidateRepository) {
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.solutionRepository = solutionRepository;
        this.solutionCoverageRepository = solutionCoverageRepository;
        this.projectSolutionRepository = projectSolutionRepository;
        this.requirementLinkRepository = requirementLinkRepository;
        this.productRepository = productRepository;
        this.productCoverageRepository = productCoverageRepository;
        this.candidateRepository = candidateRepository;
    }

    /** Replace only decision blocks while preserving all other architecture DSL. */
    @Transactional(readOnly = true)
    public String contributeTo(String dsl, String username, WorkspaceContext context) {
        DocumentAst document = parseOrEmpty(dsl);
        List<BlockAst> blocks = new ArrayList<>();
        for (BlockAst block : document.getBlocks()) {
            if (!MANAGED_BLOCKS.contains(block.getKind())) {
                blocks.add(block);
            }
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
                                    "sourceSnapshotId", link.getSnapshotId(),
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

    /** Materialize all durable decisions after projects and requirements exist. */
    @Transactional
    public DecisionMaterializeResult materialize(
            String dsl, String username, WorkspaceContext context) {
        DocumentAst document = parser.parse(dsl, "portfolio-decisions.taxdsl");
        String scopeKey = PortfolioScope.key(username, context);
        String workspaceId = PortfolioScope.workspaceId(context);
        String owner = PortfolioScope.username(username, context);
        Instant now = Instant.now();
        List<String> warnings = new ArrayList<>();

        Map<String, SolutionDefinition> solutions = materializeSolutions(
                document, scopeKey, workspaceId, owner, now, warnings);
        Map<String, ProductCatalogEntry> products = materializeProducts(
                document, scopeKey, workspaceId, owner, now, warnings);
        Map<String, ArchitectureProject> projects = projects(scopeKey);
        Map<String, ProjectSolution> projectSolutions = materializeProjectSolutions(
                document, projects, solutions, owner, now, warnings);
        int requirementLinks = materializeRequirementLinks(
                document, projects, projectSolutions, owner, now, warnings);
        int productCandidates = materializeProductCandidates(
                document, products, projectSolutions, owner, now, warnings);

        return new DecisionMaterializeResult(
                solutions.size(),
                products.size(),
                projectSolutions.size(),
                requirementLinks,
                productCandidates,
                List.copyOf(warnings));
    }

    private Map<String, SolutionDefinition> materializeSolutions(
            DocumentAst document,
            String scopeKey,
            String workspaceId,
            String owner,
            Instant now,
            List<String> warnings) {
        Map<String, SolutionDefinition> solutions = new LinkedHashMap<>();
        for (BlockAst block : document.blocksOfKind(SOLUTION_BLOCK)) {
            String key = header(block, 0, warnings);
            if (key == null) continue;
            try {
                SolutionDefinition solution = solutionRepository
                        .findByScopeKeyAndSolutionKeyIgnoreCase(scopeKey, key)
                        .orElse(null);
                if (solution == null) {
                    solution = new SolutionDefinition(
                            scopeKey,
                            workspaceId,
                            key,
                            required(block, "title", key),
                            block.property("description"),
                            enumValue(SolutionType.class, block.property("solutionType"), SolutionType.OTHER),
                            enumValue(OperatingModel.class, block.property("operatingModel"), OperatingModel.UNSPECIFIED),
                            enumValue(LifecycleStatus.class, block.property("lifecycleStatus"), LifecycleStatus.PLANNED),
                            clamp(integer(block.property("maturityLevel"), 0), 0, 5),
                            valueOrDefault(block.property("owner"), owner),
                            block.property("responsibleOrganization"),
                            now);
                }
                solution.update(
                        required(block, "title", solution.getTitle()),
                        block.property("description"),
                        enumValue(SolutionType.class, block.property("solutionType"), solution.getSolutionType()),
                        enumValue(OperatingModel.class, block.property("operatingModel"), solution.getOperatingModel()),
                        enumValue(LifecycleStatus.class, block.property("lifecycleStatus"), solution.getLifecycleStatus()),
                        clamp(integer(block.property("maturityLevel"), solution.getMaturityLevel()), 0, 5),
                        valueOrDefault(block.property("owner"), solution.getOwnerUsername()),
                        block.property("responsibleOrganization"),
                        decimal(block.property("costAmount")),
                        normalizeCurrency(block.property("costCurrency")),
                        block.property("riskNotes"),
                        nullableInteger(block.property("leadTimeDays")),
                        block.property("extensionAttributes"),
                        now);
                solution = solutionRepository.save(solution);
                solutions.put(key, solution);
            } catch (RuntimeException exception) {
                warnings.add(identity(block) + ": " + exception.getMessage());
            }
        }

        for (BlockAst block : document.blocksOfKind(SOLUTION_TAXONOMY_BLOCK)) {
            String solutionKey = header(block, 0, warnings);
            String nodeCode = header(block, 1, warnings);
            SolutionDefinition solution = solutions.get(solutionKey);
            if (solution == null || nodeCode == null) {
                warnings.add(identity(block) + " references unavailable solution data");
                continue;
            }
            SolutionTaxonomyCoverage coverage = solutionCoverageRepository
                    .findBySolutionIdAndNodeCode(solution.getId(), nodeCode)
                    .orElse(null);
            int percent = clamp(integer(block.property("coveragePercent"), 0), 0, 100);
            ReviewStatus review = enumValue(
                    ReviewStatus.class, block.property("reviewStatus"), ReviewStatus.PROPOSED);
            if (coverage == null) {
                coverage = new SolutionTaxonomyCoverage(
                        solution, nodeCode, percent, block.property("evidence"), review,
                        valueOrDefault(block.property("updatedBy"), owner), now);
            } else {
                coverage.update(
                        percent,
                        block.property("evidence"),
                        review,
                        valueOrDefault(block.property("updatedBy"), owner),
                        now);
            }
            solutionCoverageRepository.save(coverage);
        }
        return solutions;
    }

    private Map<String, ProductCatalogEntry> materializeProducts(
            DocumentAst document,
            String scopeKey,
            String workspaceId,
            String owner,
            Instant now,
            List<String> warnings) {
        Map<String, ProductCatalogEntry> products = new LinkedHashMap<>();
        for (BlockAst block : document.blocksOfKind(PRODUCT_BLOCK)) {
            String key = header(block, 0, warnings);
            if (key == null) continue;
            String sourceReference = block.property("sourceReference");
            Instant verifiedAt = instant(block.property("verifiedAt"));
            if (isBlank(sourceReference) || verifiedAt == null) {
                warnings.add(identity(block) + " requires sourceReference and verifiedAt");
                continue;
            }
            try {
                ProductCatalogEntry product = productRepository
                        .findByScopeKeyAndProductKeyIgnoreCase(scopeKey, key)
                        .orElse(null);
                if (product == null) {
                    product = new ProductCatalogEntry(
                            scopeKey,
                            workspaceId,
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
                            normalizeCurrency(block.property("costCurrency")),
                            block.property("costBasis"),
                            sourceReference,
                            verifiedAt,
                            valueOrDefault(block.property("createdBy"), owner),
                            now);
                } else {
                    product.update(
                            required(block, "manufacturer", product.getManufacturer()),
                            block.property("productFamily"),
                            required(block, "productName", product.getProductName()),
                            block.property("editionVersion"),
                            enumValue(ProductStatus.class, block.property("productStatus"), product.getProductStatus()),
                            date(block.property("endOfSupport")),
                            block.property("licenseModel"),
                            enumValue(OperatingModel.class, block.property("operatingModel"), product.getOperatingModel()),
                            block.property("supportedPlatforms"),
                            block.property("securityFeatures"),
                            block.property("complianceFeatures"),
                            decimal(block.property("costAmount")),
                            normalizeCurrency(block.property("costCurrency")),
                            block.property("costBasis"),
                            sourceReference,
                            verifiedAt,
                            now);
                }
                product = productRepository.save(product);
                products.put(key, product);
            } catch (RuntimeException exception) {
                warnings.add(identity(block) + ": " + exception.getMessage());
            }
        }

        for (BlockAst block : document.blocksOfKind(PRODUCT_TAXONOMY_BLOCK)) {
            String productKey = header(block, 0, warnings);
            String nodeCode = header(block, 1, warnings);
            ProductCatalogEntry product = products.get(productKey);
            if (product == null || nodeCode == null) {
                warnings.add(identity(block) + " references unavailable product data");
                continue;
            }
            ProductTaxonomyCoverage coverage = productCoverageRepository
                    .findByProductIdAndNodeCode(product.getId(), nodeCode)
                    .orElse(null);
            int percent = clamp(integer(block.property("coveragePercent"), 0), 0, 100);
            ReviewStatus review = enumValue(
                    ReviewStatus.class, block.property("reviewStatus"), ReviewStatus.PROPOSED);
            if (coverage == null) {
                coverage = new ProductTaxonomyCoverage(
                        product, nodeCode, percent, block.property("evidence"), review,
                        valueOrDefault(block.property("updatedBy"), owner), now);
            } else {
                coverage.update(
                        percent,
                        block.property("evidence"),
                        review,
                        valueOrDefault(block.property("updatedBy"), owner),
                        now);
            }
            productCoverageRepository.save(coverage);
        }
        return products;
    }

    private Map<String, ArchitectureProject> projects(String scopeKey) {
        Map<String, ArchitectureProject> projects = new LinkedHashMap<>();
        for (ArchitectureProject project : projectRepository
                .findByScopeKeyOrderByUpdatedAtDesc(scopeKey)) {
            projects.put(project.getProjectKey(), project);
        }
        return projects;
    }

    private Map<String, ProjectSolution> materializeProjectSolutions(
            DocumentAst document,
            Map<String, ArchitectureProject> projects,
            Map<String, SolutionDefinition> solutions,
            String owner,
            Instant now,
            List<String> warnings) {
        Map<String, ProjectSolution> projectSolutions = new LinkedHashMap<>();
        for (BlockAst block : document.blocksOfKind(PROJECT_SOLUTION_BLOCK)) {
            String projectKey = header(block, 0, warnings);
            String solutionKey = header(block, 1, warnings);
            ArchitectureProject project = projects.get(projectKey);
            SolutionDefinition solution = solutions.get(solutionKey);
            if (project == null || solution == null) {
                warnings.add(identity(block) + " references unavailable project or solution data");
                continue;
            }
            ProjectSolution projectSolution = projectSolutionRepository
                    .findByProjectIdAndSolutionId(project.getId(), solution.getId())
                    .orElse(null);
            ProjectSolutionStatus status = enumValue(
                    ProjectSolutionStatus.class, block.property("status"), ProjectSolutionStatus.PROPOSED);
            ActionStatus action = enumValue(
                    ActionStatus.class, block.property("actionStatus"), ActionStatus.UNDECIDED);
            int priority = clamp(integer(block.property("priority"), 50), 0, 100);
            if (projectSolution == null) {
                projectSolution = new ProjectSolution(
                        project,
                        solution,
                        status,
                        action,
                        priority,
                        block.property("rationale"),
                        valueOrDefault(block.property("createdBy"), owner),
                        now);
            } else {
                projectSolution.update(
                        status,
                        action,
                        priority,
                        block.property("rationale"),
                        now);
            }
            projectSolution = projectSolutionRepository.save(projectSolution);
            projectSolutions.put(composite(projectKey, solutionKey), projectSolution);
        }
        return projectSolutions;
    }

    private int materializeRequirementLinks(
            DocumentAst document,
            Map<String, ArchitectureProject> projects,
            Map<String, ProjectSolution> projectSolutions,
            String owner,
            Instant now,
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
                warnings.add(identity(block) + " references unavailable requirement data");
                continue;
            }
            RequirementSolutionLink link = requirementLinkRepository
                    .findByProjectSolutionIdAndRequirementId(
                            projectSolution.getId(), requirement.getId())
                    .orElse(null);
            String sourceSnapshotId = block.property("sourceSnapshotId");
            int percent = clamp(integer(block.property("coveragePercent"), 0), 0, 100);
            RequirementSolutionRole role = enumValue(
                    RequirementSolutionRole.class, block.property("role"), RequirementSolutionRole.USES);
            ReviewStatus review = enumValue(
                    ReviewStatus.class, block.property("reviewStatus"), ReviewStatus.PROPOSED);
            if (link == null) {
                link = new RequirementSolutionLink(
                        projectSolution,
                        requirement,
                        sourceSnapshotId,
                        percent,
                        role,
                        review,
                        block.property("evidence"),
                        valueOrDefault(block.property("updatedBy"), owner),
                        now);
                count++;
            } else {
                link.update(
                        sourceSnapshotId,
                        percent,
                        role,
                        review,
                        block.property("evidence"),
                        valueOrDefault(block.property("updatedBy"), owner),
                        now);
            }
            requirementLinkRepository.save(link);
        }
        return count;
    }

    private int materializeProductCandidates(
            DocumentAst document,
            Map<String, ProductCatalogEntry> products,
            Map<String, ProjectSolution> projectSolutions,
            String owner,
            Instant now,
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
            SolutionProductCandidate candidate = candidateRepository
                    .findByProjectSolutionIdAndProductId(
                            projectSolution.getId(), product.getId())
                    .orElse(null);
            int percent = clamp(integer(block.property("coveragePercent"), 0), 0, 100);
            double confidence = clamp(decimalDouble(block.property("confidence"), 0.0), 0.0, 1.0);
            ReviewStatus review = enumValue(
                    ReviewStatus.class, block.property("reviewStatus"), ReviewStatus.PROPOSED);
            ProductSelectionStatus selection = enumValue(
                    ProductSelectionStatus.class,
                    block.property("selectionStatus"),
                    ProductSelectionStatus.CANDIDATE);
            String exclusions = block.property("hardExclusions");
            if (selection == ProductSelectionStatus.SELECTED
                    && (review != ReviewStatus.CONFIRMED || !isBlank(exclusions))) {
                warnings.add(identity(block)
                        + " cannot remain SELECTED without confirmed review and no hard exclusions");
                selection = ProductSelectionStatus.CANDIDATE;
            }
            if (candidate == null) {
                candidate = new SolutionProductCandidate(
                        projectSolution,
                        product,
                        percent,
                        exclusions,
                        block.property("strengths"),
                        block.property("weaknesses"),
                        block.property("openEvidence"),
                        confidence,
                        review,
                        selection,
                        valueOrDefault(block.property("updatedBy"), owner),
                        now);
                count++;
            } else {
                candidate.update(
                        percent,
                        exclusions,
                        block.property("strengths"),
                        block.property("weaknesses"),
                        block.property("openEvidence"),
                        confidence,
                        review,
                        selection,
                        valueOrDefault(block.property("updatedBy"), owner),
                        now);
            }
            candidateRepository.save(candidate);
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String valueOrDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value;
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

    private static String normalizeCurrency(String value) {
        if (isBlank(value)) return null;
        String normalized = value.strip().toUpperCase();
        return normalized.matches("[A-Z]{3}") ? normalized : null;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
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
            int solutionsMaterialized,
            int productsMaterialized,
            int projectSolutionsMaterialized,
            int requirementLinksCreated,
            int productCandidatesCreated,
            List<String> warnings) {
        public DecisionMaterializeResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
