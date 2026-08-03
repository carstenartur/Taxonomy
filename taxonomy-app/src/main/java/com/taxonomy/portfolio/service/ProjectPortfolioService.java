package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ImportRequirementCandidate;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateRequirementRequest;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.PortfolioTypes.ConflictStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProjectConflictRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.repository.ProjectSolutionRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Application service for workspace-isolated projects and immutable requirement versions. */
@Service
public class ProjectPortfolioService {

    private static final Pattern BUSINESS_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final ArchitectureProjectRepository projectRepository;
    private final ProjectRequirementRepository requirementRepository;
    private final ProjectRequirementVersionRepository versionRepository;
    private final ProjectSolutionRepository projectSolutionRepository;
    private final ProjectConflictRepository conflictRepository;
    private final PortfolioFingerprintService fingerprintService;
    private final PortfolioJsonCodec jsonCodec;

    public ProjectPortfolioService(ArchitectureProjectRepository projectRepository,
                                   ProjectRequirementRepository requirementRepository,
                                   ProjectRequirementVersionRepository versionRepository,
                                   ProjectSolutionRepository projectSolutionRepository,
                                   ProjectConflictRepository conflictRepository,
                                   PortfolioFingerprintService fingerprintService,
                                   PortfolioJsonCodec jsonCodec) {
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.versionRepository = versionRepository;
        this.projectSolutionRepository = projectSolutionRepository;
        this.conflictRepository = conflictRepository;
        this.fingerprintService = fingerprintService;
        this.jsonCodec = jsonCodec;
    }

    @Transactional
    public ProjectView createProject(CreateProjectRequest request,
                                     String username,
                                     WorkspaceContext context) {
        requireNonNull(request, "project request");
        String scopeKey = PortfolioScope.key(username, context);
        String projectKey = normalizeBusinessKey(request.projectKey(), "projectKey");
        String title = requireText(request.title(), "title", 240);
        if (projectRepository.existsByScopeKeyAndProjectKeyIgnoreCase(scopeKey, projectKey)) {
            throw PortfolioException.conflict("Project key already exists in this workspace: " + projectKey);
        }

        var budgetAmount = PortfolioValueValidator.money(request.budgetAmount(), "budgetAmount");
        String budgetCurrency = normalizeCurrency(request.budgetCurrency());
        PortfolioValueValidator.requireMoneyPair(
                budgetAmount, budgetCurrency, "budgetAmount", "budgetCurrency");

        Instant now = Instant.now();
        ArchitectureProject project = new ArchitectureProject(
                scopeKey,
                PortfolioScope.workspaceId(context),
                PortfolioScope.username(username, context),
                projectKey,
                title,
                limited(request.description(), 4000, "description"),
                request.status() != null ? request.status() : ProjectStatus.PLANNING,
                now);
        project.update(
                null,
                null,
                null,
                limited(request.targetArchitecture(), 4000, "targetArchitecture"),
                request.targetDate(),
                budgetAmount,
                budgetCurrency,
                now);
        return toProjectView(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public List<ProjectView> listProjects(String username, WorkspaceContext context) {
        return projectRepository.findByScopeKeyOrderByUpdatedAtDesc(PortfolioScope.key(username, context))
                .stream().map(this::toProjectView).toList();
    }

    @Transactional(readOnly = true)
    public ProjectView getProject(Long projectId, String username, WorkspaceContext context) {
        return toProjectView(requireProject(projectId, username, context));
    }

    @Transactional
    public ProjectView updateProject(Long projectId,
                                     UpdateProjectRequest request,
                                     String username,
                                     WorkspaceContext context) {
        requireNonNull(request, "project update");
        ArchitectureProject project = requireProject(projectId, username, context);
        var requestedAmount = request.budgetAmount() != null
                ? PortfolioValueValidator.money(request.budgetAmount(), "budgetAmount") : null;
        String requestedCurrency = request.budgetCurrency() != null
                ? normalizeCurrency(request.budgetCurrency()) : null;
        var effectiveAmount = request.budgetAmount() != null
                ? requestedAmount : project.getBudgetAmount();
        String effectiveCurrency = request.budgetCurrency() != null
                ? requestedCurrency : project.getBudgetCurrency();
        PortfolioValueValidator.requireMoneyPair(
                effectiveAmount, effectiveCurrency, "budgetAmount", "budgetCurrency");

        project.update(
                request.title() != null ? requireText(request.title(), "title", 240) : null,
                limited(request.description(), 4000, "description"),
                request.status(),
                limited(request.targetArchitecture(), 4000, "targetArchitecture"),
                request.targetDate(),
                requestedAmount,
                requestedCurrency,
                Instant.now());
        return toProjectView(project);
    }

    @Transactional
    public RequirementView createRequirement(Long projectId,
                                             CreateRequirementRequest request,
                                             String username,
                                             WorkspaceContext context) {
        requireNonNull(request, "requirement request");
        ArchitectureProject project = requireProject(projectId, username, context);
        String requirementKey = normalizeBusinessKey(request.requirementKey(), "requirementKey");
        if (requirementRepository.findByProjectIdAndRequirementKeyIgnoreCase(projectId, requirementKey).isPresent()) {
            throw PortfolioException.conflict(
                    "Requirement key already exists in project " + project.getProjectKey() + ": " + requirementKey);
        }

        int priority = normalizePriority(request.priority());
        Instant now = Instant.now();
        ProjectRequirement requirement = new ProjectRequirement(
                project,
                requirementKey,
                requireText(request.title(), "title", 240),
                request.status() != null ? request.status() : RequirementStatus.DRAFT,
                priority,
                request.criticality() != null ? request.criticality() : Criticality.MEDIUM,
                request.requirementType() != null ? request.requirementType() : RequirementType.FUNCTIONAL,
                request.reviewStatus() != null ? request.reviewStatus() : ReviewStatus.PROPOSED,
                request.ownerUsername() != null && !request.ownerUsername().isBlank()
                        ? requireText(request.ownerUsername(), "ownerUsername", 160)
                        : PortfolioScope.username(username, context),
                now);
        requirementRepository.save(requirement);
        ProjectRequirementVersion version = createVersionEntity(
                requirement,
                requireText(request.text(), "text", 100_000),
                request.changeReason(),
                request.source(),
                PortfolioScope.username(username, context),
                1,
                now);
        versionRepository.save(version);
        requirement.pointToVersion(version.getId(), now);
        return toRequirementView(requirement, version);
    }

    @Transactional
    public List<RequirementView> importRequirements(Long projectId,
                                                    List<ImportRequirementCandidate> candidates,
                                                    String username,
                                                    WorkspaceContext context) {
        if (candidates == null || candidates.isEmpty()) {
            throw PortfolioException.validation("At least one requirement candidate is required");
        }
        List<RequirementView> imported = new ArrayList<>(candidates.size());
        for (ImportRequirementCandidate candidate : candidates) {
            if (candidate == null) continue;
            imported.add(createRequirement(projectId, new CreateRequirementRequest(
                    candidate.requirementKey(),
                    candidate.title(),
                    candidate.text(),
                    RequirementStatus.DRAFT,
                    candidate.priority(),
                    candidate.criticality(),
                    candidate.requirementType(),
                    ReviewStatus.PROPOSED,
                    username,
                    "Imported as an individual requirement candidate",
                    candidate.source()), username, context));
        }
        if (imported.isEmpty()) {
            throw PortfolioException.validation("No valid requirement candidates were supplied");
        }
        return List.copyOf(imported);
    }

    @Transactional(readOnly = true)
    public List<RequirementView> listRequirements(Long projectId,
                                                  String username,
                                                  WorkspaceContext context) {
        requireProject(projectId, username, context);
        return requirementRepository.findByProjectIdOrderByRequirementKeyAsc(projectId).stream()
                .map(requirement -> toRequirementView(requirement, currentVersion(requirement)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RequirementView getRequirement(Long projectId,
                                          Long requirementId,
                                          String username,
                                          WorkspaceContext context) {
        ProjectRequirement requirement = requireRequirement(projectId, requirementId, username, context);
        return toRequirementView(requirement, currentVersion(requirement));
    }

    @Transactional
    public RequirementView updateRequirement(Long projectId,
                                             Long requirementId,
                                             UpdateRequirementRequest request,
                                             String username,
                                             WorkspaceContext context) {
        requireNonNull(request, "requirement update");
        ProjectRequirement requirement = requireRequirement(projectId, requirementId, username, context);
        requirement.updateMetadata(
                request.title() != null ? requireText(request.title(), "title", 240) : null,
                request.status(),
                request.priority() != null ? normalizePriority(request.priority()) : null,
                request.criticality(),
                request.requirementType(),
                request.reviewStatus(),
                request.ownerUsername() != null && !request.ownerUsername().isBlank()
                        ? requireText(request.ownerUsername(), "ownerUsername", 160) : null,
                Instant.now());
        return toRequirementView(requirement, currentVersion(requirement));
    }

    @Transactional
    public RequirementVersionView addRequirementVersion(Long projectId,
                                                        Long requirementId,
                                                        CreateRequirementVersionRequest request,
                                                        String username,
                                                        WorkspaceContext context) {
        requireNonNull(request, "requirement version request");
        ProjectRequirement requirement = requireRequirementForUpdate(
                projectId, requirementId, username, context);
        String text = requireText(request.text(), "text", 100_000);
        String contentHash = fingerprintService.contentFingerprint(text);
        ProjectRequirementVersion existing = versionRepository
                .findByRequirementIdAndContentHash(requirementId, contentHash)
                .orElse(null);
        if (existing != null) {
            requirement.pointToVersion(existing.getId(), Instant.now());
            return toVersionView(existing);
        }

        int nextVersion = versionRepository.findFirstByRequirementIdOrderByVersionNumberDesc(requirementId)
                .map(last -> last.getVersionNumber() + 1)
                .orElse(1);
        Instant now = Instant.now();
        ProjectRequirementVersion version = createVersionEntity(
                requirement,
                text,
                request.changeReason(),
                request.source(),
                PortfolioScope.username(username, context),
                nextVersion,
                now);
        versionRepository.save(version);
        requirement.pointToVersion(version.getId(), now);
        return toVersionView(version);
    }

    @Transactional(readOnly = true)
    public List<RequirementVersionView> listRequirementVersions(Long projectId,
                                                               Long requirementId,
                                                               String username,
                                                               WorkspaceContext context) {
        requireRequirement(projectId, requirementId, username, context);
        return versionRepository.findByRequirementIdOrderByVersionNumberDesc(requirementId)
                .stream().map(this::toVersionView).toList();
    }

    @Transactional(readOnly = true)
    public ArchitectureProject requireProject(Long projectId,
                                              String username,
                                              WorkspaceContext context) {
        if (projectId == null) throw PortfolioException.validation("projectId is required");
        return projectRepository.findByIdAndScopeKey(projectId, PortfolioScope.key(username, context))
                .orElseThrow(() -> PortfolioException.notFound("Project not found: " + projectId));
    }

    @Transactional(readOnly = true)
    public ProjectRequirement requireRequirement(Long projectId,
                                                 Long requirementId,
                                                 String username,
                                                 WorkspaceContext context) {
        requireProject(projectId, username, context);
        if (requirementId == null) throw PortfolioException.validation("requirementId is required");
        return requirementRepository.findByIdAndProjectId(requirementId, projectId)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Requirement " + requirementId + " was not found in project " + projectId));
    }

    private ProjectRequirement requireRequirementForUpdate(Long projectId,
                                                           Long requirementId,
                                                           String username,
                                                           WorkspaceContext context) {
        requireProject(projectId, username, context);
        if (requirementId == null) throw PortfolioException.validation("requirementId is required");
        return requirementRepository.findByIdAndProjectIdForUpdate(requirementId, projectId)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Requirement " + requirementId + " was not found in project " + projectId));
    }

    @Transactional(readOnly = true)
    public ProjectRequirementVersion currentVersion(ProjectRequirement requirement) {
        Objects.requireNonNull(requirement, "requirement");
        if (requirement.getCurrentVersionId() != null) {
            return versionRepository.findByIdAndRequirementId(
                            requirement.getCurrentVersionId(), requirement.getId())
                    .orElseThrow(() -> PortfolioException.notFound(
                            "Current requirement version not found: " + requirement.getCurrentVersionId()));
        }
        return versionRepository.findFirstByRequirementIdOrderByVersionNumberDesc(requirement.getId())
                .orElseThrow(() -> PortfolioException.notFound(
                        "Requirement has no text version: " + requirement.getRequirementKey()));
    }

    @Transactional(readOnly = true)
    public ProjectView toProjectView(ArchitectureProject project) {
        int requirementCount = Math.toIntExact(requirementRepository.countByProjectId(project.getId()));
        int solutionCount = projectSolutionRepository.findByProjectIdOrderByPriorityDescSolutionTitleAsc(
                project.getId()).size();
        int openConflicts = (int) conflictRepository
                .findByProjectIdOrderByConfidenceDescDetectedAtDesc(project.getId()).stream()
                .filter(conflict -> conflict.getStatus() != ConflictStatus.REJECTED
                        && conflict.getStatus() != ConflictStatus.RESOLVED)
                .count();
        return new ProjectView(
                project.getId(),
                project.getProjectKey(),
                project.getTitle(),
                project.getDescription(),
                project.getStatus(),
                project.getOwnerUsername(),
                project.getWorkspaceId(),
                project.getTargetArchitecture(),
                project.getTargetDate(),
                project.getBudgetAmount(),
                project.getBudgetCurrency(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                requirementCount,
                solutionCount,
                openConflicts);
    }

    public RequirementView toRequirementView(ProjectRequirement requirement,
                                             ProjectRequirementVersion currentVersion) {
        return new RequirementView(
                requirement.getId(),
                requirement.getProject().getId(),
                requirement.getRequirementKey(),
                requirement.getTitle(),
                requirement.getStatus(),
                requirement.getPriority(),
                requirement.getCriticality(),
                requirement.getRequirementType(),
                requirement.getReviewStatus(),
                requirement.getOwnerUsername(),
                requirement.getCurrentVersionId(),
                requirement.getCurrentAnalysisSnapshotId(),
                requirement.getCreatedAt(),
                requirement.getUpdatedAt(),
                currentVersion != null ? toVersionView(currentVersion) : null);
    }

    public RequirementVersionView toVersionView(ProjectRequirementVersion version) {
        return new RequirementVersionView(
                version.getId(),
                version.getVersionNumber(),
                version.getText(),
                version.getContentHash(),
                version.getChangeReason(),
                version.getCreatedBy(),
                version.getCreatedAt(),
                new SourceReference(
                        version.getSourceArtifactId(),
                        version.getSourceVersionId(),
                        jsonCodec.readLongList(version.getSourceFragmentIdsJson()),
                        version.getSectionReference(),
                        version.getPageNumber(),
                        version.getOriginalText()));
    }

    private ProjectRequirementVersion createVersionEntity(ProjectRequirement requirement,
                                                           String text,
                                                           String changeReason,
                                                           SourceReference source,
                                                           String createdBy,
                                                           int versionNumber,
                                                           Instant now) {
        List<Long> fragmentIds = sourceFragmentIds(source);
        Long sourceArtifactId = source != null
                ? positiveIdentifier(source.sourceArtifactId(), "sourceArtifactId") : null;
        Long sourceVersionId = source != null
                ? positiveIdentifier(source.sourceVersionId(), "sourceVersionId") : null;
        Integer pageNumber = source != null ? positivePageNumber(source.pageNumber()) : null;
        return new ProjectRequirementVersion(
                requirement,
                versionNumber,
                text,
                fingerprintService.contentFingerprint(text),
                limited(changeReason, 1000, "changeReason"),
                createdBy,
                now,
                sourceArtifactId,
                sourceVersionId,
                jsonCodec.write(fragmentIds),
                source != null ? limited(source.sectionReference(), 500, "sectionReference") : null,
                pageNumber,
                source != null
                        ? limited(source.originalText(),
                                PortfolioValueValidator.MAX_ORIGINAL_TEXT_CHARACTERS,
                                "originalText")
                        : null);
    }

    private static List<Long> sourceFragmentIds(SourceReference source) {
        if (source == null || source.sourceFragmentIds() == null) return List.of();
        if (source.sourceFragmentIds().size() > PortfolioValueValidator.MAX_SOURCE_FRAGMENT_IDS) {
            throw PortfolioException.validation(
                    "sourceFragmentIds contains more than "
                            + PortfolioValueValidator.MAX_SOURCE_FRAGMENT_IDS + " entries");
        }
        List<Long> fragmentIds = source.sourceFragmentIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (fragmentIds.stream().anyMatch(id -> id <= 0)) {
            throw PortfolioException.validation("sourceFragmentIds must contain only positive identifiers");
        }
        return fragmentIds;
    }

    private static Long positiveIdentifier(Long value, String field) {
        if (value != null && value <= 0) {
            throw PortfolioException.validation(field + " must be a positive identifier");
        }
        return value;
    }

    private static Integer positivePageNumber(Integer value) {
        if (value != null && value < 1) {
            throw PortfolioException.validation("pageNumber must be positive");
        }
        return value;
    }

    private static int normalizePriority(Integer priority) {
        int normalized = priority != null ? priority : 50;
        if (normalized < 0 || normalized > 100) {
            throw PortfolioException.validation("priority must be between 0 and 100");
        }
        return normalized;
    }

    private static String normalizeBusinessKey(String value, String field) {
        String normalized = requireText(value, field, 64).toUpperCase(Locale.ROOT);
        if (!BUSINESS_KEY.matcher(normalized).matches()) {
            throw PortfolioException.validation(
                    field + " must start with a letter or digit and contain only letters, digits, '.', '_' or '-'");
        }
        return normalized;
    }

    private static String normalizeCurrency(String currency) {
        return PortfolioValueValidator.currency(currency, "budgetCurrency");
    }

    static String requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw PortfolioException.validation(field + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw PortfolioException.validation(field + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }

    static String limited(String value, int maximumLength, String field) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw PortfolioException.validation(field + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }

    private static void requireNonNull(Object value, String label) {
        if (value == null) throw PortfolioException.validation(label + " is required");
    }
}
