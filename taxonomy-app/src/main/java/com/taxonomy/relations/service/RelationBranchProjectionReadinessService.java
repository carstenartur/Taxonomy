package com.taxonomy.relations.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.model.RelationDecisionProjectionCheckpoint;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Proves whether one branch projection is complete for the current Git head.
 *
 * <p>This service is intentionally not wired into user-facing relation reads yet.
 * It establishes the fail-closed boundary required for that later switch: missing,
 * stale, partial, tombstone-contaminated or concurrently advanced projections are
 * never returned as ready.</p>
 */
@Service
public class RelationBranchProjectionReadinessService {

    private final DslGitRepositoryFactory gitRepositoryFactory;
    private final RelationDecisionProjectionRepository projectionRepository;
    private final RelationDecisionProjectionCheckpointRepository checkpointRepository;

    public RelationBranchProjectionReadinessService(
            DslGitRepositoryFactory gitRepositoryFactory,
            RelationDecisionProjectionRepository projectionRepository,
            RelationDecisionProjectionCheckpointRepository checkpointRepository) {
        this.gitRepositoryFactory = Objects.requireNonNull(
                gitRepositoryFactory, "gitRepositoryFactory");
        this.projectionRepository = Objects.requireNonNull(
                projectionRepository, "projectionRepository");
        this.checkpointRepository = Objects.requireNonNull(
                checkpointRepository, "checkpointRepository");
    }

    /**
     * Returns the current Git head commit for the given branch without loading
     * or validating projection rows.  Use this when only the head SHA is needed
     * (e.g. to seed the expected-head for a review command).
     */
    public String readCurrentHead(RepositoryContext context) {
        Objects.requireNonNull(context, "context");
        DslGitRepository repository = gitRepositoryFactory.resolveRepository(context);
        return readHead(repository, context.branch());
    }

    @Transactional(readOnly = true)
    public Readiness inspect(RepositoryContext context) {
        Objects.requireNonNull(context, "context");
        DslGitRepository repository = gitRepositoryFactory.resolveRepository(context);
        String headBefore = readHead(repository, context.branch());
        if (headBefore == null) {
            return Readiness.notReady(
                    ReadinessState.BRANCH_MISSING,
                    null,
                    null,
                    List.of());
        }

        String workspaceScopeKey = RelationDecisionProjection.scopeKeyFor(
                context.workspaceId());
        RelationDecisionProjectionCheckpoint checkpoint = checkpointRepository
                .findByRepositoryIdAndWorkspaceScopeKeyAndBranch(
                        context.repositoryId(),
                        workspaceScopeKey,
                        context.branch())
                .orElse(null);
        if (checkpoint == null) {
            return Readiness.notReady(
                    ReadinessState.NOT_BUILT,
                    headBefore,
                    null,
                    List.of());
        }
        if (!headBefore.equals(checkpoint.getAuthoritativeCommitId())) {
            return Readiness.notReady(
                    ReadinessState.STALE,
                    headBefore,
                    checkpoint.getAuthoritativeCommitId(),
                    List.of());
        }

        List<RelationDecisionProjection> rows = projectionRepository
                .findByRepositoryIdAndWorkspaceScopeKeyAndBranchOrderBySourceCodeAscTargetCodeAsc(
                        context.repositoryId(),
                        workspaceScopeKey,
                        context.branch());
        boolean inconsistent = rows.size() != checkpoint.getRelationCount()
                || rows.stream().anyMatch(row -> !row.isRelationPresent()
                        || !headBefore.equals(row.getAuthoritativeCommitId()));
        if (inconsistent) {
            return Readiness.notReady(
                    ReadinessState.CORRUPT,
                    headBefore,
                    checkpoint.getAuthoritativeCommitId(),
                    List.of());
        }

        String headAfter = readHead(repository, context.branch());
        if (!Objects.equals(headBefore, headAfter)) {
            return Readiness.notReady(
                    ReadinessState.STALE,
                    headAfter,
                    checkpoint.getAuthoritativeCommitId(),
                    List.of());
        }
        return new Readiness(
                ReadinessState.READY,
                headBefore,
                checkpoint.getAuthoritativeCommitId(),
                List.copyOf(rows));
    }

    public List<RelationDecisionProjection> requireReady(RepositoryContext context) {
        Readiness readiness = inspect(context);
        if (readiness.state() != ReadinessState.READY) {
            throw new RelationProjectionNotReadyException(
                    "Relation projection is not ready for "
                            + context.repositoryId() + "/" + context.branch()
                            + ": " + readiness.state());
        }
        return readiness.rows();
    }

    private static String readHead(
            DslGitRepository repository,
            String branch) {
        try {
            Ref ref = repository.getGitRepository().getRefDatabase()
                    .exactRef(Constants.R_HEADS + branch);
            return ref == null || ref.getObjectId() == null
                    ? null
                    : ref.getObjectId().name();
        } catch (IOException error) {
            throw new RelationProjectionNotReadyException(
                    "Unable to read selected relation projection branch head",
                    error);
        }
    }

    public enum ReadinessState {
        READY,
        NOT_BUILT,
        STALE,
        CORRUPT,
        BRANCH_MISSING
    }

    public record Readiness(
            ReadinessState state,
            String currentHeadCommit,
            String projectedCommit,
            List<RelationDecisionProjection> rows) {
        public Readiness {
            state = Objects.requireNonNull(state, "state");
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            if (state != ReadinessState.READY && !rows.isEmpty()) {
                throw new IllegalArgumentException(
                        "Non-ready projection must not expose relation rows");
            }
        }

        private static Readiness notReady(
                ReadinessState state,
                String currentHeadCommit,
                String projectedCommit,
                List<RelationDecisionProjection> rows) {
            return new Readiness(
                    state, currentHeadCommit, projectedCommit, rows);
        }
    }

    public static final class RelationProjectionNotReadyException
            extends IllegalStateException {
        public RelationProjectionNotReadyException(String message) {
            super(message);
        }

        public RelationProjectionNotReadyException(
                String message,
                Throwable cause) {
            super(message, cause);
        }
    }
}
