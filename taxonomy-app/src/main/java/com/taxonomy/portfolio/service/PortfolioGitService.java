package com.taxonomy.portfolio.service;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.MetaAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.ast.SourceLocation;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateRequirementRequest;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.model.RequirementElementMapping;
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.repository.RequirementElementMappingRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects the durable project/requirement portfolio into the canonical Git DSL
 * and materializes merged DSL blocks back into a workspace projection.
 *
 * <p>Operational analysis jobs and large immutable snapshot payloads remain in
 * the database. Stable project identities, requirement versions and current
 * requirement-to-taxonomy decisions are Git-native and can be merged between
 * contributors.</p>
 */
@Service
public class PortfolioGitService {

    public static final String PROJECT_BLOCK = "project";
    public static final String REQUIREMENT_BLOCK = "projectRequirement";
    public static final String VERSION_BLOCK = "requirementVersion";
    public static final String MANAGED_PROPERTY = "x-portfolio-managed";

    private static final Set<String> OWNED_BLOCKS =
            Set.of(PROJECT_BLOCK, REQUIREMENT_BLOCK, VERSION_BLOCK);
    private static final SourceLocation GENERATED =
            new SourceLocation("portfolio-projection", 1, 1);

    private final ProjectPortfolioService projectService;
    private final ArchitectureProjectRepository projectRepository;
    private final ProjectRequirementRepository requirementRepository;
    private final ProjectRequirementVersionRepository versionRepository;
    private final RequirementElementMappingRepository elementMappingRepository;
    private final DslGitRepositoryFactory repositoryFactory;
    private final TaxDslParser parser = new TaxDslParser();
    private final TaxDslSerializer serializer = new TaxDslSerializer();

    public PortfolioGitService(ProjectPortfolioService projectService,
                               ArchitectureProjectRepository projectRepository,
                               ProjectRequirementRepository requirementRepository,
                               ProjectRequirementVersionRepository versionRepository,
                               RequirementElementMappingRepository elementMappingRepository,
                               DslGitRepositoryFactory repositoryFactory) {
        this.projectService = projectService;
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.versionRepository = versionRepository;
        this.elementMappingRepository = elementMappingRepository;
        this.repositoryFactory = repositoryFactory;
    }

    /** Return a standalone deterministic DSL document for the current workspace portfolio. */
    @Transactional(readOnly = true)
    public String exportPortfolio(String username, WorkspaceContext context) {
        DocumentAst document = new DocumentAst(
                new MetaAst(MetaAst.LANGUAGE_ID, MetaAst.CURRENT_VERSION,
                        "portfolio", GENERATED),
                portfolioBlocks(username, context));
        return serializer.serialize(document);
    }

    /** Replace only portfolio-owned blocks while preserving the remaining architecture DSL. */
    @Transactional(readOnly = true)
    public String contributeTo(String existingDsl, String username, WorkspaceContext context) {
        DocumentAst existing = parseOrEmpty(existingDsl);
        List<BlockAst> blocks = new ArrayList<>();
        for (BlockAst block : existing.getBlocks()) {
            if (!isPortfolioManaged(block)) blocks.add(block);
        }
        blocks.addAll(portfolioBlocks(username, context));
        MetaAst meta = existing.getMeta() != null
                ? existing.getMeta()
                : new MetaAst(MetaAst.LANGUAGE_ID, MetaAst.CURRENT_VERSION,
                        "default", GENERATED);
        return serializer.serialize(new DocumentAst(meta, blocks));
    }

    /** Commit the complete current portfolio projection to a branch. */
    @Transactional(readOnly = true)
    public CommitResult commit(String branch,
                               String message,
                               String username,
                               WorkspaceContext context) throws IOException {
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String current = repository.getDslAtHead(branch);
        String projected = contributeTo(current, username, context);
        if (Objects.equals(normalize(current), normalize(projected))) {
            return new CommitResult(repository.getHeadCommit(branch), false, branch);
        }
        String commit = repository.commitDsl(
                branch,
                projected,
                username,
                message == null || message.isBlank()
                        ? "Update Git-backed project requirement portfolio"
                        : message.strip());
        return new CommitResult(commit, true, branch);
    }

    /** Materialize the portfolio blocks at a branch HEAD into the target workspace. */
    public MaterializeResult materializeHead(String branch,
                                             String username,
                                             WorkspaceContext context) throws IOException {
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String dsl = repository.getDslAtHead(branch);
        if (dsl == null || dsl.isBlank()) {
            return new MaterializeResult(0, 0, 0, List.of("Branch has no DSL content"));
        }
        return materialize(dsl, username, context);
    }

    /**
     * Upsert projects, requirement identities and immutable versions from DSL.
     * Deletions are intentionally not propagated automatically; removing business
     * records requires an explicit archival decision rather than a Git omission.
     */
    @Transactional
    public MaterializeResult materialize(String dsl,
                                         String username,
                                         WorkspaceContext context) {
        DocumentAst document = parser.parse(dsl, "portfolio-materialize.taxdsl");
        List<String> warnings = new ArrayList<>();
        Map<String, BlockAst> projectBlocks = keyed(document, PROJECT_BLOCK, 1, warnings);
        Map<String, BlockAst> requirementBlocks = keyed(document, REQUIREMENT_BLOCK, 2, warnings);
        Map<String, List<BlockAst>> versionBlocks = groupedVersions(document, warnings);

        int projects = 0;
        int requirements = 0;
        int versions = 0;
        String scopeKey = PortfolioScope.key(username, context);
        Map<String, ProjectView> materializedProjects = new LinkedHashMap<>();

        for (Map.Entry<String, BlockAst> entry : projectBlocks.entrySet()) {
            String projectKey = entry.getKey();
            BlockAst block = entry.getValue();
            ArchitectureProject existing = projectRepository
                    .findByScopeKeyAndProjectKeyIgnoreCase(scopeKey, projectKey)
                    .orElse(null);
            ProjectView view;
            if (existing == null) {
                view = projectService.createProject(new CreateProjectRequest(
                        projectKey,
                        required(block, "title", projectKey),
                        block.property("description"),
                        enumValue(ProjectStatus.class, block.property("status"), ProjectStatus.PLANNING),
                        block.property("targetArchitecture"),
                        dateValue(block.property("targetDate"), warnings, identity(block)),
                        decimalValue(block.property("budgetAmount"), warnings, identity(block)),
                        block.property("budgetCurrency")), username, context);
                projects++;
            } else {
                view = projectService.updateProject(existing.getId(), new UpdateProjectRequest(
                        required(block, "title", existing.getTitle()),
                        block.property("description"),
                        enumValue(ProjectStatus.class, block.property("status"), existing.getStatus()),
                        block.property("targetArchitecture"),
                        dateValue(block.property("targetDate"), warnings, identity(block)),
                        decimalValue(block.property("budgetAmount"), warnings, identity(block)),
                        block.property("budgetCurrency")), username, context);
            }
            materializedProjects.put(projectKey, view);
        }

        List<Map.Entry<String, BlockAst>> orderedRequirements = requirementBlocks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for (Map.Entry<String, BlockAst> entry : orderedRequirements) {
            String composite = entry.getKey();
            String[] keys = composite.split("\\u0000", -1);
            String projectKey = keys[0];
            String requirementKey = keys[1];
            ProjectView project = materializedProjects.get(projectKey);
            if (project == null) {
                warnings.add("Requirement " + projectKey + "/" + requirementKey
                        + " references a project block that is not available");
                continue;
            }
            BlockAst block = entry.getValue();
            List<BlockAst> versionsForRequirement = versionBlocks
                    .getOrDefault(composite, List.of()).stream()
                    .sorted(Comparator.comparingInt(this::versionNumber))
                    .toList();
            if (versionsForRequirement.isEmpty()) {
                warnings.add("Requirement " + projectKey + "/" + requirementKey
                        + " has no requirementVersion block");
                continue;
            }

            ProjectRequirement existingRequirement = requirementRepository
                    .findByProjectIdAndRequirementKeyIgnoreCase(project.id(), requirementKey)
                    .orElse(null);
            RequirementView requirementView;
            BlockAst firstVersion = versionsForRequirement.getFirst();
            if (existingRequirement == null) {
                requirementView = projectService.createRequirement(
                        project.id(),
                        new CreateRequirementRequest(
                                requirementKey,
                                required(block, "title", requirementKey),
                                required(firstVersion, "text", ""),
                                enumValue(RequirementStatus.class, block.property("status"), RequirementStatus.DRAFT),
                                intValue(block.property("priority"), 50, warnings, identity(block)),
                                enumValue(Criticality.class, block.property("criticality"), Criticality.MEDIUM),
                                enumValue(RequirementType.class, block.property("requirementType"), RequirementType.FUNCTIONAL),
                                enumValue(ReviewStatus.class, block.property("reviewStatus"), ReviewStatus.PROPOSED),
                                valueOrDefault(block.property("owner"), username),
                                firstVersion.property("changeReason"),
                                sourceReference(firstVersion, warnings)),
                        username,
                        context);
                requirements++;
                versions++;
            } else {
                requirementView = projectService.updateRequirement(
                        project.id(), existingRequirement.getId(),
                        new UpdateRequirementRequest(
                                required(block, "title", existingRequirement.getTitle()),
                                enumValue(RequirementStatus.class, block.property("status"), existingRequirement.getStatus()),
                                intValue(block.property("priority"), existingRequirement.getPriority(), warnings, identity(block)),
                                enumValue(Criticality.class, block.property("criticality"), existingRequirement.getCriticality()),
                                enumValue(RequirementType.class, block.property("requirementType"), existingRequirement.getRequirementType()),
                                enumValue(ReviewStatus.class, block.property("reviewStatus"), existingRequirement.getReviewStatus()),
                                valueOrDefault(block.property("owner"), existingRequirement.getOwnerUsername())),
                        username,
                        context);
            }

            for (BlockAst version : versionsForRequirement) {
                String text = required(version, "text", "");
                if (text.equals(requirementView.currentVersion().text())) continue;
                projectService.addRequirementVersion(
                        project.id(), requirementView.id(),
                        new CreateRequirementVersionRequest(
                                text,
                                version.property("changeReason"),
                                sourceReference(version, warnings)),
                        username,
                        context);
                versions++;
            }
        }
        return new MaterializeResult(projects, requirements, versions, List.copyOf(warnings));
    }

    private List<BlockAst> portfolioBlocks(String username, WorkspaceContext context) {
        List<BlockAst> result = new ArrayList<>();
        String scopeKey = PortfolioScope.key(username, context);
        List<ArchitectureProject> projects = projectRepository
                .findByScopeKeyOrderByUpdatedAtDesc(scopeKey).stream()
                .sorted(Comparator.comparing(ArchitectureProject::getProjectKey))
                .toList();

        for (ArchitectureProject project : projects) {
            result.add(block(PROJECT_BLOCK, List.of(project.getProjectKey()), properties(
                    "title", project.getTitle(),
                    "description", project.getDescription(),
                    "status", name(project.getStatus()),
                    "owner", project.getOwnerUsername(),
                    "workspaceId", project.getWorkspaceId(),
                    "targetArchitecture", project.getTargetArchitecture(),
                    "targetDate", string(project.getTargetDate()),
                    "budgetAmount", string(project.getBudgetAmount()),
                    "budgetCurrency", project.getBudgetCurrency(),
                    MANAGED_PROPERTY, "true")));

            List<ProjectRequirement> requirements = requirementRepository
                    .findByProjectIdOrderByRequirementKeyAsc(project.getId());
            Map<Long, List<RequirementElementMapping>> mappingsByRequirement =
                    mappingsByRequirement(project.getId());
            for (ProjectRequirement requirement : requirements) {
                result.add(block(REQUIREMENT_BLOCK,
                        List.of(project.getProjectKey(), requirement.getRequirementKey()), properties(
                                "title", requirement.getTitle(),
                                "status", name(requirement.getStatus()),
                                "priority", Integer.toString(requirement.getPriority()),
                                "criticality", name(requirement.getCriticality()),
                                "requirementType", name(requirement.getRequirementType()),
                                "reviewStatus", name(requirement.getReviewStatus()),
                                "owner", requirement.getOwnerUsername(),
                                "currentVersionId", string(requirement.getCurrentVersionId()),
                                "currentSnapshotId", requirement.getCurrentAnalysisSnapshotId(),
                                MANAGED_PROPERTY, "true")));

                List<ProjectRequirementVersion> versions = versionRepository
                        .findByRequirementIdOrderByVersionNumberDesc(requirement.getId()).stream()
                        .sorted(Comparator.comparingInt(ProjectRequirementVersion::getVersionNumber))
                        .toList();
                for (ProjectRequirementVersion version : versions) {
                    result.add(block(VERSION_BLOCK,
                            List.of(project.getProjectKey(), requirement.getRequirementKey(),
                                    Integer.toString(version.getVersionNumber())), properties(
                                    "text", version.getText(),
                                    "contentHash", version.getContentHash(),
                                    "changeReason", version.getChangeReason(),
                                    "createdBy", version.getCreatedBy(),
                                    "createdAt", string(version.getCreatedAt()),
                                    "sourceArtifactId", string(version.getSourceArtifactId()),
                                    "sourceVersionId", string(version.getSourceVersionId()),
                                    "sourceFragmentIds", version.getSourceFragmentIdsJson(),
                                    "sectionReference", version.getSectionReference(),
                                    "pageNumber", string(version.getPageNumber()),
                                    "originalText", version.getOriginalText(),
                                    MANAGED_PROPERTY, "true")));
                }

                ProjectRequirementVersion current = versions.stream()
                        .filter(version -> Objects.equals(version.getId(), requirement.getCurrentVersionId()))
                        .findFirst()
                        .orElse(versions.isEmpty() ? null : versions.getLast());
                if (current == null) continue;
                String canonicalId = canonicalRequirementId(
                        project.getProjectKey(), requirement.getRequirementKey());
                result.add(block("requirement", List.of(canonicalId), properties(
                        "title", requirement.getTitle(),
                        "text", current.getText(),
                        MANAGED_PROPERTY, "true",
                        "x-project-key", project.getProjectKey(),
                        "x-requirement-key", requirement.getRequirementKey(),
                        "x-version", Integer.toString(current.getVersionNumber()),
                        "x-content-hash", current.getContentHash(),
                        "x-review-status", name(requirement.getReviewStatus()),
                        "x-owner", requirement.getOwnerUsername())));

                for (RequirementElementMapping mapping : mappingsByRequirement
                        .getOrDefault(requirement.getId(), List.of())) {
                    result.add(block("mapping",
                            List.of(canonicalId, "->", mapping.getNodeCode()), properties(
                                    "score", Integer.toString(mapping.getDirectScore()),
                                    "source", mapping.getSnapshot().getId(),
                                    MANAGED_PROPERTY, "true",
                                    "x-relevance", Double.toString(mapping.getRelevance()),
                                    "x-confidence", Double.toString(mapping.getConfidence()),
                                    "x-origin", name(mapping.getMappingOrigin()),
                                    "x-review-status", name(mapping.getReviewStatus()),
                                    "x-action-status", name(mapping.getActionStatus()),
                                    "x-evidence", mapping.getActionEvidence(),
                                    "x-decision-by", mapping.getDecisionBy(),
                                    "x-decision-at", string(mapping.getDecisionAt()),
                                    "x-decision-comment", mapping.getDecisionComment())));
                }
            }
        }
        return result;
    }

    private Map<Long, List<RequirementElementMapping>> mappingsByRequirement(Long projectId) {
        Map<Long, List<RequirementElementMapping>> result = new HashMap<>();
        for (RequirementElementMapping mapping : elementMappingRepository
                .findCurrentMappingsForProject(projectId)) {
            Long requirementId = mapping.getSnapshot().getRequirement().getId();
            result.computeIfAbsent(requirementId, ignored -> new ArrayList<>()).add(mapping);
        }
        return result;
    }

    private boolean isPortfolioManaged(BlockAst block) {
        return OWNED_BLOCKS.contains(block.getKind())
                || (Set.of("requirement", "mapping").contains(block.getKind())
                && "true".equalsIgnoreCase(block.property(MANAGED_PROPERTY)));
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

    private Map<String, BlockAst> keyed(DocumentAst document,
                                        String kind,
                                        int headerSize,
                                        List<String> warnings) {
        Map<String, BlockAst> result = new LinkedHashMap<>();
        for (BlockAst block : document.blocksOfKind(kind)) {
            if (block.getHeaderTokens().size() < headerSize) {
                warnings.add("Invalid " + kind + " header at " + block.getSourceLocation());
                continue;
            }
            String key = headerSize == 1
                    ? block.getHeaderTokens().getFirst()
                    : block.getHeaderTokens().get(0) + "\u0000" + block.getHeaderTokens().get(1);
            if (result.putIfAbsent(key, block) != null) {
                warnings.add("Duplicate " + kind + " block: " + key.replace('\u0000', '/'));
            }
        }
        return result;
    }

    private Map<String, List<BlockAst>> groupedVersions(DocumentAst document,
                                                         List<String> warnings) {
        Map<String, List<BlockAst>> result = new LinkedHashMap<>();
        for (BlockAst block : document.blocksOfKind(VERSION_BLOCK)) {
            if (block.getHeaderTokens().size() < 3) {
                warnings.add("Invalid requirementVersion header at " + block.getSourceLocation());
                continue;
            }
            String key = block.getHeaderTokens().get(0) + "\u0000" + block.getHeaderTokens().get(1);
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(block);
        }
        return result;
    }

    private int versionNumber(BlockAst block) {
        try {
            return Integer.parseInt(block.getHeaderTokens().get(2));
        } catch (RuntimeException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private SourceReference sourceReference(BlockAst block, List<String> warnings) {
        return new SourceReference(
                longValue(block.property("sourceArtifactId"), warnings, identity(block)),
                longValue(block.property("sourceVersionId"), warnings, identity(block)),
                longList(block.property("sourceFragmentIds"), warnings, identity(block)),
                block.property("sectionReference"),
                integerValue(block.property("pageNumber"), warnings, identity(block)),
                block.property("originalText"));
    }

    private static BlockAst block(String kind, List<String> header, List<PropertyAst> properties) {
        return new BlockAst(kind, header, properties, List.of(), Map.of(), GENERATED);
    }

    private static List<PropertyAst> properties(String... pairs) {
        List<PropertyAst> properties = new ArrayList<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            String value = pairs[index + 1];
            if (value != null) {
                properties.add(new PropertyAst(pairs[index], value, GENERATED));
            }
        }
        return properties;
    }

    private static String canonicalRequirementId(String projectKey, String requirementKey) {
        return (projectKey + "__" + requirementKey).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String identity(BlockAst block) {
        return block.getKind() + " " + String.join(" ", block.getHeaderTokens());
    }

    private static String required(BlockAst block, String property, String fallback) {
        String value = block.property(property);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.strip().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static LocalDate dateValue(String value,
                                       List<String> warnings,
                                       String identity) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            warnings.add(identity + " has invalid date: " + value);
            return null;
        }
    }

    private static BigDecimal decimalValue(String value,
                                           List<String> warnings,
                                           String identity) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (RuntimeException exception) {
            warnings.add(identity + " has invalid decimal: " + value);
            return null;
        }
    }

    private static int intValue(String value,
                                int fallback,
                                List<String> warnings,
                                String identity) {
        Integer parsed = integerValue(value, warnings, identity);
        return parsed != null ? parsed : fallback;
    }

    private static Integer integerValue(String value,
                                        List<String> warnings,
                                        String identity) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value);
        } catch (RuntimeException exception) {
            warnings.add(identity + " has invalid integer: " + value);
            return null;
        }
    }

    private static Long longValue(String value,
                                  List<String> warnings,
                                  String identity) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.valueOf(value);
        } catch (RuntimeException exception) {
            warnings.add(identity + " has invalid long value: " + value);
            return null;
        }
    }

    private static List<Long> longList(String value,
                                       List<String> warnings,
                                       String identity) {
        if (value == null || value.isBlank()) return List.of();
        String normalized = value.replace("[", "").replace("]", "").replace("\"", "");
        List<Long> result = new ArrayList<>();
        for (String token : normalized.split(",")) {
            String trimmed = token.strip();
            if (trimmed.isEmpty()) continue;
            try {
                result.add(Long.valueOf(trimmed));
            } catch (NumberFormatException exception) {
                warnings.add(identity + " has invalid fragment ID: " + trimmed);
            }
        }
        return List.copyOf(result);
    }

    public record CommitResult(String commitId, boolean changed, String branch) {
    }

    public record MaterializeResult(
            int projectsCreated,
            int requirementsCreated,
            int versionsCreated,
            List<String> warnings) {
        public MaterializeResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
