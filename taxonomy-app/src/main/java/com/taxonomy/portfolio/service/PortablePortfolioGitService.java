package com.taxonomy.portfolio.service;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.repository.RequirementElementMappingRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Portable Git projection variant.
 *
 * <p>Database primary keys are not part of the collaboration contract. The
 * current requirement version is represented and restored by its project-local,
 * monotonically increasing version number so the same DSL can be materialized
 * in another database or workspace. Durable solution and product decisions are
 * contributed through stable business keys as part of the same Git document.</p>
 */
@Service
@Primary
public class PortablePortfolioGitService extends PortfolioGitService {

    private static final String CURRENT_VERSION_NUMBER = "currentVersionNumber";
    private static final String DATABASE_CURRENT_VERSION_ID = "currentVersionId";

    private final ArchitectureProjectRepository projectRepository;
    private final ProjectRequirementRepository requirementRepository;
    private final ProjectRequirementVersionRepository versionRepository;
    private final PortfolioDecisionGitContributor decisionContributor;
    private final TaxDslParser parser = new TaxDslParser();
    private final TaxDslSerializer serializer = new TaxDslSerializer();

    public PortablePortfolioGitService(
            ProjectPortfolioService projectService,
            ArchitectureProjectRepository projectRepository,
            ProjectRequirementRepository requirementRepository,
            ProjectRequirementVersionRepository versionRepository,
            RequirementElementMappingRepository elementMappingRepository,
            DslGitRepositoryFactory repositoryFactory,
            PortfolioDecisionGitContributor decisionContributor) {
        super(projectService, projectRepository, requirementRepository,
                versionRepository, elementMappingRepository, repositoryFactory);
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.versionRepository = versionRepository;
        this.decisionContributor = decisionContributor;
    }

    @Override
    @Transactional(readOnly = true)
    public String exportPortfolio(String username, WorkspaceContext context) {
        String requirements = addPortableCurrentVersion(
                super.exportPortfolio(username, context), username, context);
        return decisionContributor.contributeTo(requirements, username, context);
    }

    @Override
    @Transactional(readOnly = true)
    public String contributeTo(String existingDsl,
                               String username,
                               WorkspaceContext context) {
        String requirements = addPortableCurrentVersion(
                super.contributeTo(existingDsl, username, context), username, context);
        return decisionContributor.contributeTo(requirements, username, context);
    }

    @Override
    @Transactional
    public MaterializeResult materialize(String dsl,
                                         String username,
                                         WorkspaceContext context) {
        MaterializeResult result = super.materialize(dsl, username, context);
        restorePortableCurrentVersions(dsl, username, context);
        PortfolioDecisionGitContributor.DecisionMaterializeResult decisions =
                decisionContributor.materialize(dsl, username, context);
        List<String> warnings = new ArrayList<>(result.warnings());
        warnings.addAll(decisions.warnings());
        return new MaterializeResult(
                result.projectsCreated(),
                result.requirementsCreated(),
                result.versionsCreated(),
                List.copyOf(warnings));
    }

    private String addPortableCurrentVersion(String dsl,
                                             String username,
                                             WorkspaceContext context) {
        Map<String, Integer> currentVersions = currentVersionNumbers(username, context);
        DocumentAst document = parser.parse(dsl, "portfolio-portable-version.taxdsl");
        List<BlockAst> blocks = new ArrayList<>(document.getBlocks().size());
        for (BlockAst block : document.getBlocks()) {
            if (!REQUIREMENT_BLOCK.equals(block.getKind())
                    || block.getHeaderTokens().size() < 2) {
                blocks.add(block);
                continue;
            }
            String identity = composite(
                    block.getHeaderTokens().get(0), block.getHeaderTokens().get(1));
            Integer versionNumber = currentVersions.get(identity);
            if (versionNumber == null) {
                blocks.add(block);
                continue;
            }
            List<PropertyAst> properties = new ArrayList<>();
            for (PropertyAst property : block.getProperties()) {
                if (!CURRENT_VERSION_NUMBER.equals(property.key())
                        && !DATABASE_CURRENT_VERSION_ID.equals(property.key())) {
                    properties.add(property);
                }
            }
            properties.add(new PropertyAst(
                    CURRENT_VERSION_NUMBER,
                    Integer.toString(versionNumber),
                    block.getSourceLocation()));
            blocks.add(new BlockAst(
                    block.getKind(),
                    block.getHeaderTokens(),
                    properties,
                    block.getChildren(),
                    block.getExtensions(),
                    block.getSourceLocation()));
        }
        return serializer.serialize(new DocumentAst(document.getMeta(), blocks));
    }

    private Map<String, Integer> currentVersionNumbers(String username,
                                                       WorkspaceContext context) {
        Map<String, Integer> result = new HashMap<>();
        String scopeKey = PortfolioScope.key(username, context);
        for (ArchitectureProject project :
                projectRepository.findByScopeKeyOrderByUpdatedAtDesc(scopeKey)) {
            for (ProjectRequirement requirement :
                    requirementRepository.findByProjectIdOrderByRequirementKeyAsc(project.getId())) {
                if (requirement.getCurrentVersionId() == null) continue;
                versionRepository.findByIdAndRequirementId(
                                requirement.getCurrentVersionId(), requirement.getId())
                        .ifPresent(version -> result.put(
                                composite(project.getProjectKey(), requirement.getRequirementKey()),
                                version.getVersionNumber()));
            }
        }
        return result;
    }

    private void restorePortableCurrentVersions(String dsl,
                                                String username,
                                                WorkspaceContext context) {
        DocumentAst document = parser.parse(dsl, "portfolio-current-version.taxdsl");
        String scopeKey = PortfolioScope.key(username, context);
        Instant now = Instant.now();
        for (BlockAst block : document.blocksOfKind(REQUIREMENT_BLOCK)) {
            if (block.getHeaderTokens().size() < 2) continue;
            Integer versionNumber = parseVersionNumber(block.property(CURRENT_VERSION_NUMBER));
            if (versionNumber == null) continue;
            String projectKey = block.getHeaderTokens().get(0);
            String requirementKey = block.getHeaderTokens().get(1);
            ArchitectureProject project = projectRepository
                    .findByScopeKeyAndProjectKeyIgnoreCase(scopeKey, projectKey)
                    .orElse(null);
            if (project == null) continue;
            ProjectRequirement requirement = requirementRepository
                    .findByProjectIdAndRequirementKeyIgnoreCase(project.getId(), requirementKey)
                    .orElse(null);
            if (requirement == null) continue;
            ProjectRequirementVersion version = versionRepository
                    .findByRequirementIdAndVersionNumber(requirement.getId(), versionNumber)
                    .orElse(null);
            if (version != null
                    && !Objects.equals(requirement.getCurrentVersionId(), version.getId())) {
                requirement.pointToVersion(version.getId(), now);
            }
        }
    }

    private static Integer parseVersionNumber(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            int parsed = Integer.parseInt(value.strip());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String composite(String projectKey, String requirementKey) {
        return projectKey + '\u0000' + requirementKey;
    }
}
