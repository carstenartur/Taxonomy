package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.NodeOrigin;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobItemView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ElementMappingView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RelationMappingView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ReviewElementMappingRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ReviewRelationMappingRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ScoreChange;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDiff;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.MappingOrigin;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import com.taxonomy.portfolio.model.RequirementElementMapping;
import com.taxonomy.portfolio.model.RequirementRelationMapping;
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.portfolio.repository.RequirementElementMappingRepository;
import com.taxonomy.portfolio.repository.RequirementRelationMappingRepository;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/** Transactional exact-tenant persistence boundary for long-running project analysis. */
@Service
public class PortfolioAnalysisPersistenceService {

    private static final String AUTOMATIC_IDEMPOTENCY_PREFIX = "auto:";

    private final ArchitectureProjectRepository projectRepository;
    private final ProjectRequirementRepository requirementRepository;
    private final ProjectRequirementVersionRepository versionRepository;
    private final RequirementAnalysisJobRepository jobRepository;
    private final RequirementAnalysisJobItemRepository itemRepository;
    private final RequirementAnalysisSnapshotRepository snapshotRepository;
    private final RequirementElementMappingRepository elementRepository;
    private final RequirementRelationMappingRepository relationRepository;
    private final RelationHypothesisRepository hypothesisRepository;
    private final PortfolioJsonCodec jsonCodec;

    public PortfolioAnalysisPersistenceService(ArchitectureProjectRepository projectRepository,
                                               ProjectRequirementRepository requirementRepository,
                                               ProjectRequirementVersionRepository versionRepository,
                                               RequirementAnalysisJobRepository jobRepository,
                                               RequirementAnalysisJobItemRepository itemRepository,
                                               RequirementAnalysisSnapshotRepository snapshotRepository,
                                               RequirementElementMappingRepository elementRepository,
                                               RequirementRelationMappingRepository relationRepository,
                                               RelationHypothesisRepository hypothesisRepository,
                                               PortfolioJsonCodec jsonCodec) {
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.versionRepository = versionRepository;
        this.jobRepository = jobRepository;
        this.itemRepository = itemRepository;
        this.snapshotRepository = snapshotRepository;
        this.elementRepository = elementRepository;
        this.relationRepository = relationRepository;
        this.hypothesisRepository = hypothesisRepository;
        this.jsonCodec = jsonCodec;
    }

    @Transactional
    public AnalysisJobView createOrReuseJob(Long projectId,
                                            List<Long> requirementIds,
                                            String provider,
                                            int maxArchitectureNodes,
                                            String idempotencyKey,
                                            String username,
                                            WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        ArchitectureProject project = projectRepository
                .findByIdAndScopeKey(projectId, scopeKey)
                .orElseThrow(() -> PortfolioException.notFound("Project not found: " + projectId));

        String normalizedIdempotency = normalizeIdempotencyKey(idempotencyKey);
        if (normalizedIdempotency != null) {
            RequirementAnalysisJob existing = jobRepository
                    .findByProjectIdAndScopeKeyAndIdempotencyKey(
                            projectId, scopeKey, normalizedIdempotency)
                    .orElse(null);
            if (existing != null) {
                return toJobView(existing);
            }
        }

        if (requirementIds == null || requirementIds.isEmpty()) {
            throw PortfolioException.validation("At least one requirement is required for analysis");
        }

        List<ProjectRequirement> requirements = requirementIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(id -> requirementRepository
                        .findByIdAndProjectIdAndScopeKey(id, projectId, scopeKey)
                        .orElseThrow(() -> PortfolioException.notFound(
                                "Requirement " + id + " was not found in project " + projectId)))
                .sorted(Comparator.comparing(ProjectRequirement::getRequirementKey))
                .toList();
        if (requirements.isEmpty()) {
            throw PortfolioException.validation("At least one valid requirement is required for analysis");
        }

        Instant now = Instant.now();
        RequirementAnalysisJob job = new RequirementAnalysisJob(
                UUID.randomUUID().toString(),
                project,
                normalizedIdempotency,
                provider != null && !provider.isBlank() ? provider.strip() : null,
                maxArchitectureNodes,
                PortfolioScope.username(username, context),
                PortfolioScope.workspaceId(context),
                requirements.size(),
                now);
        jobRepository.save(job);

        List<RequirementAnalysisJobItem> items = requirements.stream()
                .map(requirement -> new RequirementAnalysisJobItem(
                        job, requirement, requireCurrentVersion(requirement)))
                .toList();
        itemRepository.saveAll(items);
        return toJobView(job);
    }

    @Transactional
    public void markJobRunning(String jobId, Long projectId, String scopeKey) {
        requireJob(jobId, projectId, scopeKey).markRunning(Instant.now());
    }

    @Transactional
    public RequirementAnalysisJobItem markItemRunning(Long itemId,
                                                      String jobId,
                                                      Long projectId,
                                                      String scopeKey) {
        RequirementAnalysisJobItem item = requireItem(itemId, jobId, projectId, scopeKey);
        item.markRunning(Instant.now());
        return item;
    }

    @Transactional
    public SnapshotSummary persistSnapshot(Long itemId,
                                           String jobId,
                                           Long projectId,
                                           String scopeKey,
                                           String snapshotId,
                                           String analysisSessionId,
                                           AnalysisResult analysis,
                                           GapAnalysisView gaps,
                                           PatternDetectionView patterns,
                                           ArchitectureRecommendation recommendation,
                                           String provider,
                                           String modelName,
                                           String promptFingerprint,
                                           String taxonomyFingerprint,
                                           String username,
                                           WorkspaceContext context,
                                           long durationMs) {
        String exactScope = requireRequestScope(scopeKey, username, context);
        RequirementAnalysisJobItem item = requireItem(
                itemId, jobId, projectId, exactScope);
        RequirementAnalysisJob job = item.getJob();
        ProjectRequirement requirement = item.getRequirement();
        AnalysisStatus status = analysisStatus(analysis);
        if (status != AnalysisStatus.SUCCESS && status != AnalysisStatus.PARTIAL) {
            throw PortfolioException.validation("Only successful or partial analyses can create snapshots");
        }

        ViewContext viewContext = analysis.getViewContext();
        RequirementAnalysisSnapshot snapshot = new RequirementAnalysisSnapshot(
                snapshotId,
                job.getProject(),
                requirement,
                item.getRequirementVersion(),
                job,
                status,
                analysisSessionId,
                provider,
                modelName,
                promptFingerprint,
                taxonomyFingerprint,
                PortfolioScope.workspaceId(context),
                viewContext != null ? viewContext.basedOnBranch() : PortfolioScope.branch(context),
                viewContext != null ? viewContext.basedOnCommit() : null,
                PortfolioScope.username(username, context),
                Instant.now(),
                durationMs,
                analysis.getWarnings() != null ? analysis.getWarnings().size() : 0,
                analysis.getErrorMessage(),
                jsonCodec.write(analysis),
                jsonCodec.write(gaps),
                jsonCodec.write(patterns),
                jsonCodec.write(recommendation));
        snapshotRepository.save(snapshot);
        persistElementMappings(snapshot, analysis);
        persistRelationMappings(snapshot, analysis);
        linkHypotheses(
                analysisSessionId,
                job.getProjectId(),
                requirement.getId(),
                snapshotId,
                context);

        requirement.pointToAnalysis(snapshotId, Instant.now());
        requirementRepository.save(requirement);
        item.complete(status, snapshotId, Instant.now());
        return toSnapshotSummary(snapshot);
    }

    @Transactional
    public void failItem(Long itemId,
                         String jobId,
                         Long projectId,
                         String scopeKey,
                         Throwable failure) {
        RequirementAnalysisJobItem item = requireItem(
                itemId, jobId, projectId, scopeKey);
        item.fail(safeFailureMessage(failure), Instant.now());
    }

    @Transactional
    public AnalysisJobView completeJob(String jobId,
                                       Long projectId,
                                       String scopeKey) {
        String exactScope = requireScope(scopeKey);
        RequirementAnalysisJob job = requireJob(jobId, projectId, exactScope);
        List<RequirementAnalysisJobItem> items = itemRepository
                .findByJobIdAndProjectIdAndScopeKeyOrderByRequirementRequirementKeyAsc(
                        jobId, projectId, exactScope);
        int successful = (int) items.stream()
                .filter(item -> item.getStatus() == AnalysisStatus.SUCCESS)
                .count();
        int partial = (int) items.stream()
                .filter(item -> item.getStatus() == AnalysisStatus.PARTIAL)
                .count();
        int failed = (int) items.stream()
                .filter(item -> item.getStatus() == AnalysisStatus.FAILED)
                .count();
        String errors = items.stream()
                .filter(item -> item.getErrorMessage() != null && !item.getErrorMessage().isBlank())
                .map(item -> item.getRequirement().getRequirementKey() + ": " + item.getErrorMessage())
                .collect(Collectors.joining(" | "));
        job.complete(successful, partial, failed,
                errors.isBlank() ? null : truncate(errors, 2000), Instant.now());
        return toJobView(job);
    }

    @Transactional
    public AnalysisJobView prepareFailedItemsForRetry(String jobId,
                                                      Long projectId,
                                                      String username,
                                                      WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        RequirementAnalysisJob job = requireScopedJob(
                jobId, projectId, username, context);
        List<RequirementAnalysisJobItem> failedItems = itemRepository
                .findByJobIdAndProjectIdAndScopeKeyAndStatusOrderByRequirementRequirementKeyAsc(
                        jobId, projectId, scopeKey, AnalysisStatus.FAILED);
        if (failedItems.isEmpty()) {
            throw PortfolioException.conflict("Analysis job has no failed items to retry: " + jobId);
        }
        for (RequirementAnalysisJobItem item : failedItems) {
            item.prepareRetry(requireCurrentVersion(item.getRequirement()));
        }
        job.markRunning(Instant.now());
        return toJobView(job);
    }

    @Transactional(readOnly = true)
    public AnalysisJobView getJob(String jobId,
                                  Long projectId,
                                  String username,
                                  WorkspaceContext context) {
        return toJobView(requireScopedJob(jobId, projectId, username, context));
    }

    @Transactional(readOnly = true)
    public List<AnalysisJobView> listJobs(Long projectId,
                                         String username,
                                         WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        requireScopedProject(projectId, username, context);
        return jobRepository
                .findByProjectIdAndScopeKeyOrderByCreatedAtDesc(projectId, scopeKey)
                .stream()
                .map(this::toJobView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SnapshotSummary> listRequirementSnapshots(Long projectId,
                                                         Long requirementId,
                                                         String username,
                                                         WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        requireScopedRequirement(projectId, requirementId, username, context);
        return snapshotRepository
                .findByRequirementIdAndProjectIdAndScopeKeyOrderByCreatedAtDesc(
                        requirementId, projectId, scopeKey)
                .stream()
                .map(this::toSnapshotSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public SnapshotDetail getSnapshot(Long projectId,
                                      String snapshotId,
                                      String username,
                                      WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        requireScopedProject(projectId, username, context);
        RequirementAnalysisSnapshot snapshot = snapshotRepository
                .findByIdAndProjectIdAndScopeKey(snapshotId, projectId, scopeKey)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Analysis snapshot not found: " + snapshotId));
        return toSnapshotDetail(snapshot);
    }

    @Transactional(readOnly = true)
    public SnapshotDiff diffSnapshots(Long projectId,
                                      String olderSnapshotId,
                                      String newerSnapshotId,
                                      String username,
                                      WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        requireScopedProject(projectId, username, context);
        RequirementAnalysisSnapshot older = snapshotRepository
                .findByIdAndProjectIdAndScopeKey(olderSnapshotId, projectId, scopeKey)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Analysis snapshot not found: " + olderSnapshotId));
        RequirementAnalysisSnapshot newer = snapshotRepository
                .findByIdAndProjectIdAndScopeKey(newerSnapshotId, projectId, scopeKey)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Analysis snapshot not found: " + newerSnapshotId));
        AnalysisResult olderAnalysis = jsonCodec.read(
                older.getAnalysisPayload(), AnalysisResult.class);
        AnalysisResult newerAnalysis = jsonCodec.read(
                newer.getAnalysisPayload(), AnalysisResult.class);

        Map<String, Integer> oldScores = olderAnalysis.getScores() != null
                ? olderAnalysis.getScores() : Map.of();
        Map<String, Integer> newScores = newerAnalysis.getScores() != null
                ? newerAnalysis.getScores() : Map.of();
        Set<String> scoreKeys = new TreeSet<>();
        scoreKeys.addAll(oldScores.keySet());
        scoreKeys.addAll(newScores.keySet());
        Map<String, ScoreChange> scoreChanges = new LinkedHashMap<>();
        for (String code : scoreKeys) {
            Integer oldScore = oldScores.get(code);
            Integer newScore = newScores.get(code);
            if (!Objects.equals(oldScore, newScore)) {
                scoreChanges.put(code, new ScoreChange(oldScore, newScore));
            }
        }

        Set<String> oldElements = elementRepository
                .findBySnapshotIdAndScopeKeyOrderByTaxonomyRootAscNodeCodeAsc(
                        olderSnapshotId, scopeKey)
                .stream()
                .map(RequirementElementMapping::getNodeCode)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> newElements = elementRepository
                .findBySnapshotIdAndScopeKeyOrderByTaxonomyRootAscNodeCodeAsc(
                        newerSnapshotId, scopeKey)
                .stream()
                .map(RequirementElementMapping::getNodeCode)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> oldRelations = relationSignatures(olderSnapshotId, scopeKey);
        Set<String> newRelations = relationSignatures(newerSnapshotId, scopeKey);

        return new SnapshotDiff(
                olderSnapshotId,
                newerSnapshotId,
                Map.copyOf(scoreChanges),
                difference(newElements, oldElements),
                difference(oldElements, newElements),
                difference(newRelations, oldRelations),
                difference(oldRelations, newRelations),
                !Objects.equals(older.getTaxonomyFingerprint(), newer.getTaxonomyFingerprint()),
                !Objects.equals(older.getPromptFingerprint(), newer.getPromptFingerprint()),
                !Objects.equals(older.getProvider(), newer.getProvider()));
    }

    @Transactional
    public ElementMappingView reviewElementMapping(Long projectId,
                                                   Long mappingId,
                                                   ReviewElementMappingRequest request,
                                                   String username,
                                                   WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        requireScopedProject(projectId, username, context);
        if (request == null) {
            throw PortfolioException.validation("mapping review is required");
        }
        RequirementElementMapping mapping = elementRepository
                .findByIdAndScopeKeyAndSnapshotProjectId(mappingId, scopeKey, projectId)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Element mapping not found: " + mappingId));
        mapping.review(
                request.reviewStatus(),
                request.actionStatus(),
                truncate(request.actionEvidence(), 2000),
                PortfolioScope.username(username, context),
                truncate(request.comment(), 2000),
                Instant.now());
        return toElementView(mapping);
    }

    @Transactional
    public RelationMappingView reviewRelationMapping(Long projectId,
                                                     Long mappingId,
                                                     ReviewRelationMappingRequest request,
                                                     String username,
                                                     WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        requireScopedProject(projectId, username, context);
        if (request == null) {
            throw PortfolioException.validation("mapping review is required");
        }
        RequirementRelationMapping mapping = relationRepository
                .findByIdAndScopeKeyAndSnapshotProjectId(mappingId, scopeKey, projectId)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Relation mapping not found: " + mappingId));
        mapping.review(
                request.reviewStatus(),
                PortfolioScope.username(username, context),
                truncate(request.comment(), 2000),
                Instant.now());
        return toRelationView(mapping);
    }

    @Transactional(readOnly = true)
    public List<RequirementAnalysisJobItem> pendingItems(String jobId,
                                                         Long projectId,
                                                         String scopeKey) {
        String exactScope = requireScope(scopeKey);
        requireJob(jobId, projectId, exactScope);
        return itemRepository
                .findByJobIdAndProjectIdAndScopeKeyAndStatusOrderByRequirementRequirementKeyAsc(
                        jobId, projectId, exactScope, AnalysisStatus.PENDING);
    }

    private void persistElementMappings(RequirementAnalysisSnapshot snapshot,
                                        AnalysisResult analysis) {
        RequirementArchitectureView architecture = analysis.getArchitectureView();
        if (architecture == null || architecture.getIncludedElements() == null) {
            return;
        }
        List<RequirementElementMapping> mappings = architecture.getIncludedElements().stream()
                .map(element -> new RequirementElementMapping(
                        snapshot,
                        element.getNodeCode(),
                        element.getTitle(),
                        taxonomyRoot(element),
                        element.getDirectLlmScore(),
                        element.getRelevance(),
                        confidence(element),
                        mappingOrigin(element.getOrigin()),
                        element.getHierarchyPath(),
                        element.getPresenceReason() != null
                                ? element.getPresenceReason() : element.getIncludedBecause(),
                        element.isSelectedForImpact()))
                .toList();
        elementRepository.saveAll(mappings);
    }

    private void persistRelationMappings(RequirementAnalysisSnapshot snapshot,
                                         AnalysisResult analysis) {
        RequirementArchitectureView architecture = analysis.getArchitectureView();
        if (architecture == null || architecture.getIncludedRelationships() == null) {
            return;
        }
        List<RequirementRelationMapping> mappings =
                architecture.getIncludedRelationships().stream()
                        .map(relation -> new RequirementRelationMapping(
                                snapshot,
                                relation.getSourceCode(),
                                relation.getTargetCode(),
                                relation.getRelationType(),
                                relation.getOrigin() != null
                                        ? relation.getOrigin().name() : "UNKNOWN",
                                relation.getRelationCategory(),
                                relation.getPropagatedRelevance(),
                                relation.getConfidence(),
                                relation.getPresenceReason() != null
                                        ? relation.getPresenceReason()
                                        : relation.getDerivationReason()))
                        .toList();
        relationRepository.saveAll(mappings);
    }

    private void linkHypotheses(String analysisSessionId,
                                Long projectId,
                                Long requirementId,
                                String snapshotId,
                                WorkspaceContext context) {
        List<RelationHypothesis> hypotheses = hypothesisRepository
                .findByAnalysisSessionIdInRepositoryWorkspace(
                        PortfolioScope.repositoryId(context),
                        PortfolioScope.workspaceId(context),
                        analysisSessionId);
        for (RelationHypothesis hypothesis : hypotheses) {
            hypothesis.setProjectId(projectId);
            hypothesis.setRequirementId(requirementId);
            hypothesis.setAnalysisSnapshotId(snapshotId);
        }
        hypothesisRepository.saveAll(hypotheses);
    }

    private RequirementAnalysisJob requireJob(String jobId,
                                              Long projectId,
                                              String scopeKey) {
        String exactScope = requireScope(scopeKey);
        return jobRepository.findByIdAndProjectIdAndScopeKey(
                        jobId, projectId, exactScope)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Analysis job not found: " + jobId));
    }

    private RequirementAnalysisJobItem requireItem(Long itemId,
                                                   String jobId,
                                                   Long projectId,
                                                   String scopeKey) {
        String exactScope = requireScope(scopeKey);
        return itemRepository.findByIdAndJobIdAndProjectIdAndScopeKey(
                        itemId, jobId, projectId, exactScope)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Analysis job item not found: " + itemId));
    }

    private RequirementAnalysisJob requireScopedJob(String jobId,
                                                    Long projectId,
                                                    String username,
                                                    WorkspaceContext context) {
        requireScopedProject(projectId, username, context);
        return requireJob(jobId, projectId, PortfolioScope.key(username, context));
    }

    private ArchitectureProject requireScopedProject(Long projectId,
                                                     String username,
                                                     WorkspaceContext context) {
        return projectRepository
                .findByIdAndScopeKey(projectId, PortfolioScope.key(username, context))
                .orElseThrow(() -> PortfolioException.notFound(
                        "Project not found: " + projectId));
    }

    private ProjectRequirement requireScopedRequirement(Long projectId,
                                                        Long requirementId,
                                                        String username,
                                                        WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        requireScopedProject(projectId, username, context);
        return requirementRepository
                .findByIdAndProjectIdAndScopeKey(requirementId, projectId, scopeKey)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Requirement " + requirementId
                                + " was not found in project " + projectId));
    }

    private ProjectRequirementVersion requireCurrentVersion(
            ProjectRequirement requirement) {
        if (requirement.getCurrentVersionId() == null) {
            throw PortfolioException.conflict(
                    "Requirement has no current text version: "
                            + requirement.getRequirementKey());
        }
        return versionRepository.findByIdAndRequirementIdAndScopeKey(
                        requirement.getCurrentVersionId(),
                        requirement.getId(),
                        requirement.getScopeKey())
                .orElseThrow(() -> PortfolioException.notFound(
                        "Current requirement version not found: "
                                + requirement.getCurrentVersionId()));
    }

    private AnalysisJobView toJobView(RequirementAnalysisJob job) {
        List<AnalysisJobItemView> items = itemRepository
                .findByJobIdAndProjectIdAndScopeKeyOrderByRequirementRequirementKeyAsc(
                        job.getId(), job.getProjectId(), job.getScopeKey())
                .stream()
                .map(this::toItemView)
                .toList();
        return new AnalysisJobView(
                job.getId(),
                job.getProjectId(),
                job.getStatus(),
                visibleIdempotencyKey(job),
                job.getProvider(),
                job.getMaxArchitectureNodes(),
                job.getRequestedBy(),
                job.getWorkspaceId(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getTotalItems(),
                job.getSuccessfulItems(),
                job.getPartialItems(),
                job.getFailedItems(),
                job.getErrorSummary(),
                items);
    }

    private AnalysisJobItemView toItemView(RequirementAnalysisJobItem item) {
        return new AnalysisJobItemView(
                item.getId(),
                item.getRequirementId(),
                item.getRequirement().getRequirementKey(),
                item.getRequirementVersionId(),
                item.getRequirementVersion().getVersionNumber(),
                item.getStatus(),
                item.getSnapshotId(),
                item.getAttempt(),
                item.getStartedAt(),
                item.getCompletedAt(),
                item.getErrorMessage());
    }

    private SnapshotDetail toSnapshotDetail(RequirementAnalysisSnapshot snapshot) {
        String scopeKey = snapshot.getScopeKey();
        return new SnapshotDetail(
                toSnapshotSummary(snapshot),
                jsonCodec.read(snapshot.getAnalysisPayload(), AnalysisResult.class),
                jsonCodec.read(snapshot.getGapAnalysisPayload(), GapAnalysisView.class),
                jsonCodec.read(snapshot.getPatternDetectionPayload(), PatternDetectionView.class),
                jsonCodec.read(
                        snapshot.getRecommendationPayload(), ArchitectureRecommendation.class),
                elementRepository
                        .findBySnapshotIdAndScopeKeyOrderByTaxonomyRootAscNodeCodeAsc(
                                snapshot.getId(), scopeKey)
                        .stream()
                        .map(this::toElementView)
                        .toList(),
                relationRepository
                        .findBySnapshotIdAndScopeKeyOrderBySourceCodeAscTargetCodeAsc(
                                snapshot.getId(), scopeKey)
                        .stream()
                        .map(this::toRelationView)
                        .toList());
    }

    private SnapshotSummary toSnapshotSummary(RequirementAnalysisSnapshot snapshot) {
        return new SnapshotSummary(
                snapshot.getId(),
                snapshot.getProjectId(),
                snapshot.getRequirementId(),
                snapshot.getRequirement().getRequirementKey(),
                snapshot.getRequirementVersionId(),
                snapshot.getRequirementVersion().getVersionNumber(),
                snapshot.getJobId(),
                snapshot.getStatus(),
                snapshot.getProvider(),
                snapshot.getModelName(),
                snapshot.getTaxonomyFingerprint(),
                snapshot.getPromptFingerprint(),
                snapshot.getWorkspaceId(),
                snapshot.getBranchName(),
                snapshot.getCommitSha(),
                snapshot.getCreatedAt(),
                snapshot.getDurationMs(),
                snapshot.getWarningCount(),
                snapshot.getErrorMessage());
    }

    private ElementMappingView toElementView(RequirementElementMapping mapping) {
        return new ElementMappingView(
                mapping.getId(),
                mapping.getSnapshotId(),
                mapping.getNodeCode(),
                mapping.getNodeTitle(),
                mapping.getTaxonomyRoot(),
                mapping.getDirectScore(),
                mapping.getRelevance(),
                mapping.getConfidence(),
                mapping.getMappingOrigin(),
                mapping.getHierarchyPath(),
                mapping.getPresenceReason(),
                mapping.isSelectedForImpact(),
                mapping.getReviewStatus(),
                mapping.getActionStatus(),
                mapping.getActionEvidence(),
                mapping.getDecisionBy(),
                mapping.getDecisionAt(),
                mapping.getDecisionComment());
    }

    private RelationMappingView toRelationView(RequirementRelationMapping mapping) {
        return new RelationMappingView(
                mapping.getId(),
                mapping.getSnapshotId(),
                mapping.getSourceCode(),
                mapping.getTargetCode(),
                mapping.getRelationType(),
                mapping.getRelationOrigin(),
                mapping.getRelationCategory(),
                mapping.getRelevance(),
                mapping.getConfidence(),
                mapping.getPresenceReason(),
                mapping.getReviewStatus(),
                mapping.getDecisionBy(),
                mapping.getDecisionAt(),
                mapping.getDecisionComment());
    }

    private Set<String> relationSignatures(String snapshotId, String scopeKey) {
        return relationRepository
                .findBySnapshotIdAndScopeKeyOrderBySourceCodeAscTargetCodeAsc(
                        snapshotId, requireScope(scopeKey))
                .stream()
                .map(mapping -> mapping.getSourceCode() + "|" + mapping.getRelationType()
                        + "|" + mapping.getTargetCode() + "|"
                        + mapping.getRelationOrigin())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static List<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return List.copyOf(result);
    }

    private static AnalysisStatus analysisStatus(AnalysisResult analysis) {
        if (analysis == null) return AnalysisStatus.FAILED;
        String status = analysis.getStatus();
        if ("SUCCESS".equalsIgnoreCase(status)) return AnalysisStatus.SUCCESS;
        if ("PARTIAL".equalsIgnoreCase(status)) return AnalysisStatus.PARTIAL;
        return AnalysisStatus.FAILED;
    }

    private static MappingOrigin mappingOrigin(NodeOrigin origin) {
        if (origin == null) return MappingOrigin.PROPAGATED;
        return switch (origin) {
            case DIRECT_SCORED -> MappingOrigin.DIRECT;
            case ENRICHED_LEAF -> MappingOrigin.ENRICHED;
            case TRACE_INTERMEDIATE, PROPAGATED, SEED_CONTEXT, IMPACT_PROMOTED ->
                    MappingOrigin.PROPAGATED;
        };
    }

    private static String taxonomyRoot(RequirementElementView element) {
        if (element.getTaxonomySheet() != null
                && !element.getTaxonomySheet().isBlank()) {
            return element.getTaxonomySheet();
        }
        String code = element.getNodeCode();
        int separator = code != null ? code.indexOf('-') : -1;
        return separator > 0 ? code.substring(0, separator) : code;
    }

    private static double confidence(RequirementElementView element) {
        if (element.getDirectLlmScore() > 0) {
            return Math.min(1.0,
                    Math.max(0.0, element.getDirectLlmScore() / 100.0));
        }
        return Math.min(1.0, Math.max(0.0, element.getRelevance()));
    }

    private static String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > 160) {
            throw PortfolioException.validation(
                    "idempotencyKey exceeds 160 characters");
        }
        return normalized;
    }

    private static String visibleIdempotencyKey(RequirementAnalysisJob job) {
        String value = job.getIdempotencyKey();
        return value != null
                && value.equals(AUTOMATIC_IDEMPOTENCY_PREFIX + job.getId())
                ? null : value;
    }

    private static String requireScope(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) {
            throw PortfolioException.validation(
                    "Exact analysis tenant scope is required");
        }
        return scopeKey.strip();
    }

    private static String requireRequestScope(String scopeKey,
                                              String username,
                                              WorkspaceContext context) {
        String supplied = requireScope(scopeKey);
        String requested = PortfolioScope.key(username, context);
        if (!requested.equals(supplied)) {
            throw PortfolioException.validation(
                    "Analysis tenant scope does not match the request context");
        }
        return requested;
    }

    private static String safeFailureMessage(Throwable failure) {
        if (failure == null) return "Unknown analysis failure";
        String type = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        return truncate(type
                + (message != null && !message.isBlank() ? ": " + message : ""),
                2000);
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null) return null;
        return value.length() <= maximumLength
                ? value : value.substring(0, maximumLength);
    }
}
