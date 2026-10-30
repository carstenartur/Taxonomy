package com.taxonomy.portfolio.service;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.requirements.RequirementRealization;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.workspace.service.WorkspacePortfolioDocumentPort;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.repository.RequirementElementMappingRepository;
import com.taxonomy.portfolio.reformulation.ReformulationEvidenceCodec;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    /** Shareable working state; ordinary collaboration exports continue to retain their declared history. */
    @Transactional(readOnly = true)
    public String exportCurrentState(String username, WorkspaceContext context) {
        return decisionContributor.contributeTo(exportCurrentPortfolio(username, context), username, context);
    }

    private static final String CURRENT_VERSION_NUMBER = "currentVersionNumber";
    private static final String DATABASE_CURRENT_VERSION_ID = "currentVersionId";

    private final ArchitectureProjectRepository projectRepository;
    private final ProjectRequirementRepository requirementRepository;
    private final ProjectRequirementVersionRepository versionRepository;
    private final PortfolioDecisionGitContributor decisionContributor;
    private final ReformulationEvidenceCodec reformulationEvidence;
    private final TaxDslParser parser = new TaxDslParser();
    private final TaxDslSerializer serializer = new TaxDslSerializer();

    public PortablePortfolioGitService(
            ProjectPortfolioService projectService,
            ArchitectureProjectRepository projectRepository,
            ProjectRequirementRepository requirementRepository,
            ProjectRequirementVersionRepository versionRepository,
            RequirementElementMappingRepository elementMappingRepository,
            WorkspacePortfolioDocumentPort repositoryFactory,
            PortfolioDecisionGitContributor decisionContributor,
            ReformulationEvidenceCodec reformulationEvidence) {
        super(projectService, projectRepository, requirementRepository,
                versionRepository, elementMappingRepository, repositoryFactory);
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.versionRepository = versionRepository;
        this.decisionContributor = decisionContributor;
        this.reformulationEvidence = reformulationEvidence;
    }

    @Override
    @Transactional(readOnly = true)
    public String exportPortfolio(String username, WorkspaceContext context) {
        String requirements = addPortableCurrentVersion(
                super.exportPortfolio(username, context), username, context);
        String decisions = decisionContributor.contributeTo(requirements, username, context);
        return reformulationEvidence.contributeTo(decisions, PortfolioScope.key(username, context));
    }

    @Override
    @Transactional(readOnly = true)
    public String contributeTo(String existingDsl,
                               String username,
                               WorkspaceContext context) {
        String requirements = addPortableCurrentVersion(
                super.contributeTo(existingDsl, username, context), username, context);
        String decisions = decisionContributor.contributeTo(requirements, username, context);
        String projection = reformulationEvidence.contributeTo(decisions, PortfolioScope.key(username, context));
        return ArchitectureSemanticPatch.applyProjection(existingDsl, projection);
    }

    @Override
    @Transactional
    public MaterializeResult materialize(String dsl,
                                         String username,
                                         WorkspaceContext context) {
        validatePortfolio(null, dsl);
        var portableEvidence = reformulationEvidence.validateForMaterialization(dsl);
        MaterializeResult result = super.materialize(dsl, username, context);
        restorePortableCurrentVersions(dsl, username, context);
        PortfolioDecisionGitContributor.DecisionMaterializeResult decisions =
                decisionContributor.materialize(dsl, username, context);
        reformulationEvidence.storeImported(portableEvidence, PortfolioScope.key(username, context));
        List<String> warnings = new ArrayList<>(result.warnings());
        warnings.addAll(decisions.warnings());
        return new MaterializeResult(
                result.projectsCreated(),
                result.requirementsCreated(),
                result.versionsCreated(),
                List.copyOf(warnings));
    }

    /** Read-only source checks used by reconciliation previews and the actual materialization boundary. */
    public void validatePortfolio(String localDsl, String candidateDsl) {
        if (localDsl != null) for (var change : ArchitectureSemanticPatch.between(localDsl, candidateDsl)) {
            if (change.before() != null && change.after() != null && change.id().startsWith(VERSION_BLOCK + ":")) {
                BlockAst version = ArchitectureSemanticPatch.index(change.before()).values().iterator().next();
                String number = version.getHeaderTokens().getLast();
                throw PortfolioException.conflict("Immutable requirement version " + number
                        + " has different source content; keep its local source and create a new requirement version for a changed decision");
            }
        }
        DocumentAst source = parser.parse(candidateDsl, "portfolio-reconciliation.taxdsl");
        Map<String, BlockAst> requirements = new HashMap<>(), versions = new HashMap<>();
        for (BlockAst block : source.getBlocks()) {
            List<String> header = block.getHeaderTokens();
            if (REQUIREMENT_BLOCK.equals(block.getKind()) && header.size() == 2)
                requirements.put(portableIdentity(header.get(0), header.get(1)), block);
            else if (VERSION_BLOCK.equals(block.getKind()) && header.size() == 3) {
                String text = block.property("text"), hash = block.property("contentHash");
                if (text == null || hash != null && !hash.equals(RequirementRealization.textHash(text)))
                    throw PortfolioException.conflict("Immutable requirement version " + String.join(" ", header)
                            + " does not match its SHA-256 content hash");
                versions.put(portableIdentity(header.get(0), header.get(1)) + '\u0000' + header.get(2), block);
            }
        }
        for (BlockAst canonical : source.blocksOfKind("requirement")) {
            if (!"true".equalsIgnoreCase(canonical.property(MANAGED_PROPERTY))) continue;
            String project = canonical.property("x-project-key"), requirement = canonical.property("x-requirement-key");
            String identity = project == null || requirement == null ? null : portableIdentity(project, requirement);
            BlockAst business = requirements.get(identity);
            Integer current = business == null ? null : parseVersionNumber(business.property(CURRENT_VERSION_NUMBER));
            BlockAst version = current == null ? null : versions.get(identity + '\u0000' + current);
            if (business == null || version == null || !Objects.equals(current, parseVersionNumber(canonical.property("x-version")))
                    || !Objects.equals(canonical.property("title"), business.property("title"))
                    || !Objects.equals(canonical.property("text"), version.property("text"))
                    || !Objects.equals(canonical.property("x-content-hash"), version.property("contentHash")))
                throw PortfolioException.conflict("Canonical requirement " + String.join(" ", canonical.getHeaderTokens())
                        + " contradicts its portable current immutable version; choose matching requirement and version source blocks");
        }
    }

    private static String portableIdentity(String project, String requirement) {
        return composite(project, requirement).toUpperCase(Locale.ROOT);
    }

    /** Source agreement alone cannot detect versions retained only in the target database. */
    @Transactional
    public void validatePortfolio(String localDsl, String candidateDsl, String username, WorkspaceContext context) {
        validatePortfolio(localDsl, candidateDsl);
        validateImmutableVersions(candidateDsl, username, context);
        reformulationEvidence.validateForMaterialization(candidateDsl);
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
            for (ProjectRequirement requirement : requirementRepository
                    .findByProjectIdAndScopeKeyOrderByRequirementKeyAsc(
                            project.getId(), scopeKey)) {
                if (requirement.getCurrentVersionId() == null) continue;
                versionRepository.findByIdAndRequirementIdAndScopeKey(
                                requirement.getCurrentVersionId(),
                                requirement.getId(),
                                scopeKey)
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
                    .findByProjectIdAndScopeKeyAndRequirementKeyIgnoreCase(
                            project.getId(), scopeKey, requirementKey)
                    .orElse(null);
            if (requirement == null) continue;
            ProjectRequirementVersion version = versionRepository
                    .findByRequirementIdAndScopeKeyAndVersionNumber(
                            requirement.getId(), scopeKey, versionNumber)
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
