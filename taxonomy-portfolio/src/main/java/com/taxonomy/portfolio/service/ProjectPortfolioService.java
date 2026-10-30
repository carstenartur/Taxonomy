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
import com.taxonomy.portfolio.repository.ProjectRequirementReadRow;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.repository.ProjectSolutionRepository;
import com.taxonomy.portfolio.reformulation.ReformulationPositiveReviewGuard;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Application service for exact-tenant projects and immutable requirement versions. */
@Service
public class ProjectPortfolioService {

    private static final Pattern BUSINESS_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final List<ConflictStatus> CLOSED_CONFLICT_STATUSES =
            List.of(ConflictStatus.REJECTED, ConflictStatus.RESOLVED);

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private final ArchitectureProjectRepository projectRepository;
    private final ProjectRequirementRepository requirementRepository;
    private final ProjectRequirementVersionRepository versionRepository;
    private final ProjectSolutionRepository projectSolutionRepository;
    private final ProjectConflictRepository conflictRepository;
    private final PortfolioFingerprintService fingerprintService;
    private final PortfolioJsonCodec jsonCodec;
    private final ReformulationPositiveReviewGuard positiveReviewGuard;

    public ProjectPortfolioService(ArchitectureProjectRepository projectRepository,
                                   ProjectRequirementRepository requirementRepository,
                                   ProjectRequirementVersionRepository versionRepository,
                                   ProjectSolutionRepository projectSolutionRepository,
                                   ProjectConflictRepository conflictRepository,
                                   PortfolioFingerprintService fingerprintService,
                                   PortfolioJsonCodec jsonCodec,
                                   ReformulationPositiveReviewGuard positiveReviewGuard) {
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.versionRepository = versionRepository;
        this.projectSolutionRepository = projectSolutionRepository;
        this.conflictRepository = conflictRepository;
        this.fingerprintService = fingerprintService;
        this.jsonCodec = jsonCodec;
        this.positiveReviewGuard = positiveReviewGuard;
    }

    @Transactional
    public ProjectView createProject(CreateProjectRequest request,
                                     String username,
                                     WorkspaceContext context) {
        request = validatedProjectInput(request);
        String scopeKey = PortfolioScope.key(username, context);
        String projectKey = request.projectKey();
        String title = request.title();
        if (projectRepository.existsByScopeKeyAndProjectKeyIgnoreCase(scopeKey, projectKey)) {
            throw PortfolioException.conflict("Project key already exists in this workspace: " + projectKey);
        }

        Instant now = Instant.now();
        ArchitectureProject project = new ArchitectureProject(
                scopeKey,
                PortfolioScope.workspaceId(context),
                PortfolioScope.username(username, context),
                projectKey,
                title,
                request.description(),
                request.status(),
                now);
        project.update(
                null,
                null,
                null,
                request.targetArchitecture(),
                request.targetDate(),
                request.budgetAmount(),
                request.budgetCurrency(),
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
        ArchitectureProject project = requireProjectForUpdate(projectId, username, context);
        request = validatedProjectInput(request, project.getBudgetAmount(), project.getBudgetCurrency());
        project.update(
                request.title(),
                request.description(),
                request.status(),
                request.targetArchitecture(),
                request.targetDate(),
                request.budgetAmount(),
                request.budgetCurrency(),
                Instant.now());
        return toProjectView(project);
    }

    @Transactional
    public RequirementView createRequirement(Long projectId,
                                             CreateRequirementRequest request,
                                             String username,
                                             WorkspaceContext context) {
        return createRequirement(projectId, request, username, context, null);
    }

    /** Frozen portable version identity; imported evidence is not attributed to the importing user. */
    public record ImportedVersion(int versionNumber, String text, String contentHash, String changeReason,
                                  SourceReference source, String createdBy, Instant createdAt) {}

    @Transactional
    public RequirementView createImportedRequirement(Long projectId, CreateRequirementRequest request,
                                                      ImportedVersion version, String username, WorkspaceContext context) {
        validateImportedVersion(version);
        return createRequirement(projectId, request, username, context, version);
    }

    private RequirementView createRequirement(Long projectId, CreateRequirementRequest request, String username,
                                               WorkspaceContext context, ImportedVersion imported) {
        request = validatedRequirementInput(request, imported);
        ArchitectureProject project = requireProjectForUpdate(projectId, username, context);
        String scopeKey = project.getScopeKey();
        String requirementKey = request.requirementKey();
        if (requirementRepository
                .findByProjectIdAndScopeKeyAndRequirementKeyIgnoreCase(
                        projectId, scopeKey, requirementKey)
                .isPresent()) {
            throw PortfolioException.conflict(
                    "Requirement key already exists in project " + project.getProjectKey() + ": " + requirementKey);
        }

        Instant now = Instant.now();
        ProjectRequirement requirement = new ProjectRequirement(
                project,
                requirementKey,
                request.title(),
                request.status(),
                request.priority(),
                request.criticality(),
                request.requirementType(),
                request.reviewStatus(),
                request.ownerUsername() != null
                        ? request.ownerUsername()
                        : PortfolioScope.username(username, context),
                now);
        requirementRepository.save(requirement);
        ProjectRequirementVersion version = createVersionEntity(
                requirement,
                request.text(),
                request.changeReason(),
                request.source(),
                imported == null || imported.createdBy() == null ? PortfolioScope.username(username, context) : imported.createdBy(),
                imported == null ? 1 : imported.versionNumber(),
                imported == null || imported.createdAt() == null ? now : imported.createdAt());
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
        ArchitectureProject project = requireProject(projectId, username, context);
        return requirementRepository
                .findReadRowsByProjectIdAndScopeKey(projectId, project.getScopeKey())
                .stream()
                .map(this::toRequirementView)
                .toList();
    }

    /** Freeze actual stored timestamp/version values inside an already active integration transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void refreshIntegrationRequirements(Long projectId, String username, WorkspaceContext context) {
        ArchitectureProject project = requireProjectForUpdate(projectId, username, context);
        entityManager.flush();
        var requirements = requirementRepository.findByProjectIdAndScopeKeyOrderByRequirementKeyAsc(projectId, project.getScopeKey());
        for (ProjectRequirement requirement : requirements) {
            entityManager.refresh(requirement);
            ProjectRequirementVersion version = currentVersion(requirement);
            if (version != null) {
                entityManager.refresh(version);
            }
        }
    }

    public record RequirementPage(List<RequirementView> requirements, boolean hasNext) {
        public RequirementPage { requirements = List.copyOf(requirements); }
    }

    /** Bound approved reads in the database, including the exact project and tenant predicates. */
    @Transactional(readOnly = true)
    public RequirementPage listApprovedRequirements(Long projectId, String username,
                                                     WorkspaceContext context, int page, int pageSize) {
        if (page < 0 || page > 100000 || pageSize < 1 || pageSize > 100)
            throw PortfolioException.validation("Requirement page is outside supported bounds");
        ArchitectureProject project = requireProject(projectId, username, context);
        var slice = requirementRepository.findReadRowsByProjectIdAndScopeKeyAndStatus(
                projectId, project.getScopeKey(), RequirementStatus.APPROVED, PageRequest.of(page, pageSize));
        return new RequirementPage(slice.getContent().stream()
                .map(this::toRequirementView)
                .toList(), slice.hasNext());
    }

    @Transactional(readOnly = true)
    public RequirementView getRequirement(Long projectId,
                                          Long requirementId,
                                          String username,
                                          WorkspaceContext context) {
        ArchitectureProject project = requireProject(projectId, username, context);
        if (requirementId == null) throw PortfolioException.validation("requirementId is required");
        return requirementRepository
                .findReadRowByIdAndProjectIdAndScopeKey(requirementId, projectId, project.getScopeKey())
                .map(this::toRequirementView)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Requirement " + requirementId + " was not found in project " + projectId));
    }

    @Transactional
    public RequirementView updateRequirement(Long projectId,
                                             Long requirementId,
                                             UpdateRequirementRequest request,
                                             String username,
                                             WorkspaceContext context) {
        requireNonNull(request, "requirement update");
        ProjectRequirement requirement = requireRequirementForUpdate(projectId, requirementId, username, context);
        request = validatedRequirementInput(request);
        validateRequirementImportMetadata(requirement, request);
        requirement.updateMetadata(
                request.title(),
                request.status(),
                request.priority(),
                request.criticality(),
                request.requirementType(),
                request.reviewStatus(),
                request.ownerUsername(),
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
        String scopeKey = requirement.getScopeKey();
        String text = rawText(request.text(), "text", 100_000);
        String contentHash = fingerprintService.contentFingerprint(text);
        ProjectRequirementVersion existing = versionRepository
                .findByRequirementIdAndScopeKeyAndContentHash(
                        requirementId, scopeKey, contentHash)
                .orElse(null);
        if (existing != null) {
            requirement.pointToVersion(existing.getId(), Instant.now());
            return toVersionView(existing);
        }

        int nextVersion = versionRepository
                .findFirstByRequirementIdAndScopeKeyOrderByVersionNumberDesc(
                        requirementId, scopeKey)
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

    /** Import an exact immutable identity. Never renumber a collision or mutate an existing version. */
    @Transactional
    public RequirementVersionView importRequirementVersion(Long projectId, Long requirementId, ImportedVersion imported,
                                                            String username, WorkspaceContext context) {
        validateImportedVersion(imported);
        ProjectRequirement requirement = requireRequirementForUpdate(projectId, requirementId, username, context);
        ProjectRequirementVersion existing = compatibleImportedVersion(requirement, imported);
        if (existing != null) return toVersionView(existing);
        Instant now = Instant.now();
        ProjectRequirementVersion version = createVersionEntity(requirement, imported.text(), imported.changeReason(),
                imported.source(), imported.createdBy() == null ? PortfolioScope.username(username, context) : imported.createdBy(),
                imported.versionNumber(), imported.createdAt() == null ? now : imported.createdAt());
        versionRepository.save(version);
        requirement.pointToVersion(version.getId(), now);
        return toVersionView(version);
    }

    /** Validate a complete incoming history without writing or moving its current-version pointer. */
    @Transactional
    public void validateRequirementVersionImports(Long projectId, Long requirementId, List<ImportedVersion> imported,
                                                  String username, WorkspaceContext context) {
        ProjectRequirement requirement = requirementId == null ? null
                : requireRequirementForUpdate(projectId, requirementId, username, context);
        Map<Integer, ImportedVersion> numbers = new java.util.HashMap<>();
        Map<String, Integer> hashes = new java.util.HashMap<>();
        for (ImportedVersion version : imported) {
            validateImportedVersion(version);
            if (numbers.putIfAbsent(version.versionNumber(), version) != null)
                throw PortfolioException.conflict("Duplicate immutable requirement version " + version.versionNumber());
            Integer prior = hashes.putIfAbsent(fingerprintService.contentFingerprint(version.text()), version.versionNumber());
            if (prior != null && prior != version.versionNumber())
                throw PortfolioException.conflict("Immutable requirement content already has a different version number");
            if (requirement != null) compatibleImportedVersion(requirement, version);
        }
    }

    private ProjectRequirementVersion compatibleImportedVersion(ProjectRequirement requirement, ImportedVersion imported) {
        Long requirementId = requirement.getId();
        String scope = requirement.getScopeKey();
        ProjectRequirementVersion existing = versionRepository.findByRequirementIdAndScopeKeyAndVersionNumber(
                requirementId, scope, imported.versionNumber()).orElse(null);
        if (existing != null) {
            if (!existing.getText().equals(imported.text())
                    || !existing.getContentHash().equals(fingerprintService.contentFingerprint(imported.text()))
                    || imported.createdBy() != null && !Objects.equals(existing.getCreatedBy(), imported.createdBy())
                    || imported.createdAt() != null && !Objects.equals(existing.getCreatedAt(), imported.createdAt())
                    || imported.changeReason() != null && !Objects.equals(existing.getChangeReason(), limited(imported.changeReason(), 1000, "changeReason"))
                    || !compatibleSource(toVersionView(existing).source(), normalizedSource(imported.source())))
                throw PortfolioException.conflict("Immutable requirement version " + imported.versionNumber() + " has different source content");
            return existing;
        }
        if (versionRepository.findByRequirementIdAndScopeKeyAndContentHash(requirementId, scope,
                fingerprintService.contentFingerprint(imported.text())).isPresent())
            throw PortfolioException.conflict("Immutable requirement content already has a different version number");
        return null;
    }

    private void validateImportedVersion(ImportedVersion version) {
        if (version == null || version.versionNumber() < 1) throw PortfolioException.validation("Imported version number must be positive");
        rawText(version.text(), "text", 100_000);
        if (version.contentHash() != null && !version.contentHash().equals(fingerprintService.contentFingerprint(version.text())))
            throw PortfolioException.conflict("Imported requirement content does not match its SHA-256 hash");
        if (version.createdBy() != null) requireText(version.createdBy(), "createdBy", 160);
        limited(version.changeReason(), 1000, "changeReason");
        normalizedSource(version.source());
    }

    private static SourceReference normalizedSource(SourceReference source) {
        return new SourceReference(source == null ? null : positiveIdentifier(source.sourceArtifactId(), "sourceArtifactId"),
                source == null ? null : positiveIdentifier(source.sourceVersionId(), "sourceVersionId"),
                sourceFragmentIds(source), source == null ? null : limited(source.sectionReference(), 500, "sectionReference"),
                source == null ? null : positivePageNumber(source.pageNumber()), source == null ? null : nullableRawText(source.originalText(),
                "originalText", PortfolioValueValidator.MAX_ORIGINAL_TEXT_CHARACTERS));
    }

    /** Missing private source fields in a current-state export cannot erase or contradict retained history. */
    private static boolean compatibleSource(SourceReference existing, SourceReference incoming) {
        return (incoming.sourceArtifactId() == null || Objects.equals(existing.sourceArtifactId(), incoming.sourceArtifactId()))
                && (incoming.sourceVersionId() == null || Objects.equals(existing.sourceVersionId(), incoming.sourceVersionId()))
                && (incoming.sourceFragmentIds().isEmpty() || Objects.equals(existing.sourceFragmentIds(), incoming.sourceFragmentIds()))
                && (incoming.sectionReference() == null || Objects.equals(existing.sectionReference(), incoming.sectionReference()))
                && (incoming.pageNumber() == null || Objects.equals(existing.pageNumber(), incoming.pageNumber()))
                && (incoming.originalText() == null || Objects.equals(existing.originalText(), incoming.originalText()));
    }

    @Transactional(readOnly = true)
    public List<RequirementVersionView> listRequirementVersions(Long projectId,
                                                               Long requirementId,
                                                               String username,
                                                               WorkspaceContext context) {
        ProjectRequirement requirement = requireRequirement(
                projectId, requirementId, username, context);
        return versionRepository
                .findByRequirementIdAndScopeKeyOrderByVersionNumberDesc(
                        requirementId, requirement.getScopeKey())
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
        ArchitectureProject project = requireProject(projectId, username, context);
        if (requirementId == null) throw PortfolioException.validation("requirementId is required");
        return requirementRepository
                .findByIdAndProjectIdAndScopeKey(
                        requirementId, projectId, project.getScopeKey())
                .orElseThrow(() -> PortfolioException.notFound(
                        "Requirement " + requirementId + " was not found in project " + projectId));
    }

    private ProjectRequirement requireRequirementForUpdate(Long projectId,
                                                           Long requirementId,
                                                           String username,
                                                           WorkspaceContext context) {
        ArchitectureProject project = requireProjectForUpdate(projectId, username, context);
        if (requirementId == null) throw PortfolioException.validation("requirementId is required");
        return requirementRepository
                .findByIdAndProjectIdAndScopeKeyForUpdate(
                        requirementId, projectId, project.getScopeKey())
                .orElseThrow(() -> PortfolioException.notFound(
                        "Requirement " + requirementId + " was not found in project " + projectId));
    }

    /** Common aggregate lock, including integration previews applied against a frozen project fingerprint. */
    @Transactional
    public ArchitectureProject requireProjectForUpdate(Long projectId, String username, WorkspaceContext context) {
        if (projectId == null) throw PortfolioException.validation("projectId is required");
        try {
            return projectRepository.findByIdAndScopeKeyForUpdate(projectId, PortfolioScope.key(username, context))
                    .orElseThrow(() -> PortfolioException.notFound("Project not found: " + projectId));
        } catch(jakarta.persistence.OptimisticLockException | org.springframework.dao.OptimisticLockingFailureException concurrent) {
            throw PortfolioException.conflict("Project inputs changed concurrently; retry the operation against current evidence");
        }
    }

    @Transactional(readOnly = true)
    public ProjectRequirementVersion currentVersion(ProjectRequirement requirement) {
        Objects.requireNonNull(requirement, "requirement");
        String scopeKey = requirement.getScopeKey();
        if (requirement.getCurrentVersionId() != null) {
            return versionRepository.findByIdAndRequirementIdAndScopeKey(
                            requirement.getCurrentVersionId(), requirement.getId(), scopeKey)
                    .orElseThrow(() -> PortfolioException.notFound(
                            "Current requirement version not found: " + requirement.getCurrentVersionId()));
        }
        return versionRepository
                .findFirstByRequirementIdAndScopeKeyOrderByVersionNumberDesc(
                        requirement.getId(), scopeKey)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Requirement has no text version: " + requirement.getRequirementKey()));
    }

    @Transactional(readOnly = true)
    public ProjectView toProjectView(ArchitectureProject project) {
        int requirementCount = Math.toIntExact(
                requirementRepository.countByProjectIdAndScopeKey(
                        project.getId(), project.getScopeKey()));
        int solutionCount = Math.toIntExact(projectSolutionRepository.countByProjectId(project.getId()));
        int openConflicts = Math.toIntExact(conflictRepository.countByProjectIdAndStatusNotIn(
                project.getId(), CLOSED_CONFLICT_STATUSES));
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

    private RequirementView toRequirementView(ProjectRequirementReadRow row) {
        if (row.versionId() == null) {
            throw PortfolioException.notFound(row.currentVersionId() != null
                    ? "Current requirement version not found: " + row.currentVersionId()
                    : "Requirement has no text version: " + row.requirementKey());
        }
        var version = new RequirementVersionView(
                row.versionId(), row.versionNumber(), row.text(), row.contentHash(), row.changeReason(),
                row.createdBy(), row.versionCreatedAt(), new SourceReference(
                        row.sourceArtifactId(), row.sourceVersionId(), jsonCodec.readLongList(row.sourceFragmentIdsJson()),
                        row.sectionReference(), row.pageNumber(), row.originalText()));
        return new RequirementView(
                row.id(), row.projectId(), row.requirementKey(), row.title(), row.status(), row.priority(),
                row.criticality(), row.requirementType(), row.reviewStatus(), row.ownerUsername(),
                row.currentVersionId(), row.currentAnalysisSnapshotId(), row.createdAt(), row.updatedAt(), version);
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
        SourceReference normalized = normalizedSource(source);
        return new ProjectRequirementVersion(
                requirement,
                versionNumber,
                text,
                fingerprintService.contentFingerprint(text),
                limited(changeReason, 1000, "changeReason"),
                createdBy,
                now,
                normalized.sourceArtifactId(),
                normalized.sourceVersionId(),
                jsonCodec.write(normalized.sourceFragmentIds()),
                normalized.sectionReference(),
                normalized.pageNumber(),
                normalized.originalText());
    }

    /** Pure owner validation shared by ordinary writes and Git import preflight. */
    static CreateProjectRequest validatedProjectInput(CreateProjectRequest request) {
        requireNonNull(request, "project request");
        BigDecimal amount = PortfolioValueValidator.money(request.budgetAmount(), "budgetAmount");
        String currency = normalizeCurrency(request.budgetCurrency());
        PortfolioValueValidator.requireMoneyPair(amount, currency, "budgetAmount", "budgetCurrency");
        return new CreateProjectRequest(normalizeBusinessKey(request.projectKey(), "projectKey"),
                requireText(request.title(), "title", 240), limited(request.description(), 4000, "description"),
                request.status() == null ? ProjectStatus.PLANNING : request.status(),
                limited(request.targetArchitecture(), 4000, "targetArchitecture"), request.targetDate(), amount, currency);
    }

    static UpdateProjectRequest validatedProjectInput(UpdateProjectRequest request, BigDecimal existingAmount, String existingCurrency) {
        requireNonNull(request, "project update");
        BigDecimal amount = PortfolioValueValidator.money(request.budgetAmount(), "budgetAmount");
        String currency = normalizeCurrency(request.budgetCurrency());
        PortfolioValueValidator.requireMoneyPair(request.budgetAmount() == null ? existingAmount : amount,
                request.budgetCurrency() == null ? existingCurrency : currency, "budgetAmount", "budgetCurrency");
        return new UpdateProjectRequest(request.title() == null ? null : requireText(request.title(), "title", 240),
                limited(request.description(), 4000, "description"), request.status(),
                limited(request.targetArchitecture(), 4000, "targetArchitecture"), request.targetDate(), amount, currency);
    }

    static CreateRequirementRequest validatedRequirementInput(CreateRequirementRequest request, ImportedVersion imported) {
        requireNonNull(request, "requirement request");
        return new CreateRequirementRequest(normalizeBusinessKey(request.requirementKey(), "requirementKey"),
                requireText(request.title(), "title", 240), rawText(imported == null ? request.text() : imported.text(), "text", 100_000),
                request.status() == null ? RequirementStatus.DRAFT : request.status(), normalizePriority(request.priority()),
                request.criticality() == null ? Criticality.MEDIUM : request.criticality(),
                request.requirementType() == null ? RequirementType.FUNCTIONAL : request.requirementType(),
                request.reviewStatus() == null ? ReviewStatus.PROPOSED : request.reviewStatus(),
                request.ownerUsername() == null || request.ownerUsername().isBlank() ? null : requireText(request.ownerUsername(), "ownerUsername", 160),
                limited(imported == null ? request.changeReason() : imported.changeReason(), 1000, "changeReason"),
                normalizedSource(imported == null ? request.source() : imported.source()));
    }

    static UpdateRequirementRequest validatedRequirementInput(UpdateRequirementRequest request) {
        requireNonNull(request, "requirement update");
        return new UpdateRequirementRequest(request.title() == null ? null : requireText(request.title(), "title", 240),
                request.status(), request.priority() == null ? null : normalizePriority(request.priority()), request.criticality(),
                request.requirementType(), request.reviewStatus(), request.ownerUsername() == null || request.ownerUsername().isBlank()
                ? null : requireText(request.ownerUsername(), "ownerUsername", 160));
    }

    /** The caller holds the aggregate lock; this checks existing review evidence without changing metadata. */
    void validateRequirementImportMetadata(ProjectRequirement requirement, UpdateRequirementRequest request) {
        validatedRequirementInput(request);
        if (request.status() == RequirementStatus.APPROVED || request.status() == RequirementStatus.IMPLEMENTING
                || request.status() == RequirementStatus.SATISFIED || request.reviewStatus() == ReviewStatus.CONFIRMED)
            positiveReviewGuard.requireReviewable(requirement.getProject(), requirement, currentVersion(requirement));
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

    static String normalizeBusinessKey(String value, String field) {
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

    private static String rawText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) throw PortfolioException.validation(field + " must not be blank");
        return nullableRawText(value, field, maximumLength);
    }

    private static String nullableRawText(String value, String field, int maximumLength) {
        if (value != null && value.length() > maximumLength) throw PortfolioException.validation(field + " exceeds " + maximumLength + " characters");
        return value;
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
