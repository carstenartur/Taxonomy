package com.taxonomy.versioning.service;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.ReviewAction;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.ReviewResult;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Objects;

/**
 * Productive {@link HypothesisService} adapter that keeps historic call sites
 * compatible while replacing their accept/reject implementation with the
 * Git-authoritative command boundary.
 */
@Service
@Primary
public class GitAuthoritativeHypothesisService extends HypothesisService {

    private final GitAuthoritativeHypothesisReviewService reviewService;
    private final RelationBranchProjectionReadinessService readinessService;
    private final SystemRepositoryService systemRepositoryService;
    private final UserWorkspaceRepository userWorkspaceRepository;

    public GitAuthoritativeHypothesisService(
            RelationHypothesisRepository hypothesisRepository,
            RelationEvidenceRepository evidenceRepository,
            TaxonomyRelationService relationService,
            TaxonomyNodeRepository nodeRepository,
            DslGitRepositoryFactory repositoryFactory,
            SystemRepositoryService systemRepositoryService,
            UserWorkspaceRepository userWorkspaceRepository,
            GitAuthoritativeHypothesisReviewService reviewService,
            RelationBranchProjectionReadinessService readinessService) {
        super(
                hypothesisRepository,
                evidenceRepository,
                relationService,
                nodeRepository,
                repositoryFactory,
                systemRepositoryService,
                userWorkspaceRepository);
        this.systemRepositoryService = Objects.requireNonNull(
                systemRepositoryService, "systemRepositoryService");
        this.userWorkspaceRepository = Objects.requireNonNull(
                userWorkspaceRepository, "userWorkspaceRepository");
        this.reviewService = Objects.requireNonNull(reviewService, "reviewService");
        this.readinessService = Objects.requireNonNull(
                readinessService, "readinessService");
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RelationHypothesis accept(
            Long hypothesisId,
            RepositoryContext context) {
        return reviewUnchecked(
                hypothesisId,
                requireContext(context),
                ReviewAction.ACCEPT).hypothesis();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RelationHypothesis accept(
            Long hypothesisId,
            WorkspaceContext workspaceContext) {
        return accept(hypothesisId, resolveLegacyContext(workspaceContext));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RelationHypothesis reject(
            Long hypothesisId,
            RepositoryContext context) {
        return reviewUnchecked(
                hypothesisId,
                requireContext(context),
                ReviewAction.REJECT).hypothesis();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RelationHypothesis reject(
            Long hypothesisId,
            WorkspaceContext workspaceContext) {
        return reject(hypothesisId, resolveLegacyContext(workspaceContext));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RelationHypothesis revert(
            Long hypothesisId,
            RepositoryContext context) {
        return reviewUnchecked(
                hypothesisId,
                requireContext(context),
                ReviewAction.REVERT).hypothesis();
    }

    public ReviewResult review(
            Long hypothesisId,
            RepositoryContext context,
            String expectedHeadCommit,
            CommandMetadata metadata,
            ReviewAction action) throws IOException {
        return switch (Objects.requireNonNull(action, "action")) {
            case ACCEPT -> reviewService.accept(
                    hypothesisId, context, expectedHeadCommit, metadata);
            case REJECT -> reviewService.reject(
                    hypothesisId, context, expectedHeadCommit, metadata);
            case REVERT -> reviewService.revert(
                    hypothesisId, context, expectedHeadCommit, metadata);
        };
    }

    /** Validate exact tenant visibility and lifecycle before exposing branch state. */
    public void requireReviewable(
            Long hypothesisId,
            RepositoryContext context,
            ReviewAction action) {
        reviewService.requireReviewable(
                hypothesisId,
                requireContext(context),
                action);
    }

    private ReviewResult reviewUnchecked(
            Long hypothesisId,
            RepositoryContext context,
            ReviewAction action) {
        requireReviewable(hypothesisId, context, action);
        Readiness readiness = readinessService.inspect(context);
        String expectedHead = readiness.currentHeadCommit();
        if (expectedHead == null) {
            throw new IllegalStateException(
                    "Selected hypothesis branch does not have an authoritative Git head");
        }
        CommandMetadata metadata = new CommandMetadata(
                "legacy-hypothesis-"
                        + action.name().toLowerCase()
                        + "-" + hypothesisId + "-" + expectedHead,
                "Git-first hypothesis review through a compatibility call site");
        try {
            return review(
                    hypothesisId,
                    context,
                    expectedHead,
                    metadata,
                    action);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Git hypothesis review failed before an authority commit was created",
                    error);
        }
    }

    private RepositoryContext resolveLegacyContext(WorkspaceContext workspaceContext) {
        WorkspaceContext legacy = workspaceContext != null
                ? workspaceContext : WorkspaceContext.SHARED;
        String username = normalizeUsername(legacy.username());
        String branch = normalizeOptional(legacy.currentBranch());
        String workspaceId = normalizeOptional(legacy.workspaceId());

        if (workspaceId == null) {
            SystemRepository primary = systemRepositoryService.getPrimaryRepository();
            return RepositoryContext.centralRead(
                    primary.getRepositoryId(),
                    branch != null ? branch : primary.getDefaultBranch(),
                    username);
        }

        UserWorkspace workspace = userWorkspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workspace not found while resolving repository context: "
                                + workspaceId));
        if (workspace.getUsername() != null
                && !workspace.getUsername().equals(username)
                && !"system".equals(username)) {
            throw new IllegalArgumentException(
                    "Workspace does not belong to the active user: " + workspaceId);
        }
        String repositoryId = requireText(
                workspace.getSourceRepositoryId(),
                "workspace.sourceRepositoryId");
        String workspaceBranch = branch != null
                ? branch : normalizeOptional(workspace.getCurrentBranch());
        return RepositoryContext.workspace(
                repositoryId,
                workspaceId,
                workspaceBranch != null ? workspaceBranch : "draft",
                username);
    }

    private static RepositoryContext requireContext(RepositoryContext context) {
        return Objects.requireNonNull(context, "context");
    }

    private static String normalizeUsername(String value) {
        String normalized = normalizeOptional(value);
        return normalized != null ? normalized : "system";
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
