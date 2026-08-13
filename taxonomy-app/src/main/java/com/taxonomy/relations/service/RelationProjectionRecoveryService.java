package com.taxonomy.relations.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.model.RelationProjectionRecovery;
import com.taxonomy.relations.model.RelationProjectionRecovery.RecoveryStatus;
import com.taxonomy.relations.repository.RelationProjectionRecoveryRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Durable recovery ledger for failed relation decision projections. */
@Service
public class RelationProjectionRecoveryService {

    private final RelationProjectionRecoveryRepository recoveryRepository;
    private final DslGitRepositoryFactory gitRepositoryFactory;

    public RelationProjectionRecoveryService(
            RelationProjectionRecoveryRepository recoveryRepository,
            DslGitRepositoryFactory gitRepositoryFactory) {
        this.recoveryRepository = Objects.requireNonNull(
                recoveryRepository, "recoveryRepository");
        this.gitRepositoryFactory = Objects.requireNonNull(
                gitRepositoryFactory, "gitRepositoryFactory");
    }

    /**
     * Records projection failure in an independent transaction after Git has
     * already committed the authoritative decision.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecoveryRecord recordPending(
            CommandResult authority,
            Throwable failure) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(failure, "failure");
        String scopeKey = RelationDecisionProjection.scopeKeyFor(
                authority.workspaceId());
        RelationProjectionRecovery recovery = recoveryRepository
                .findAuthorityForUpdate(
                        authority.repositoryId(),
                        scopeKey,
                        authority.branch(),
                        authority.authoritativeCommitId())
                .orElseGet(RelationProjectionRecovery::new);
        recovery.setRepositoryId(authority.repositoryId());
        recovery.setWorkspaceId(authority.workspaceId());
        recovery.setBranch(authority.branch());
        recovery.setPreviousHeadCommit(authority.previousHeadCommit());
        recovery.setAuthoritativeCommitId(authority.authoritativeCommitId());
        recovery.setCausationId(authority.causationId());
        recovery.recordFailure(failure);
        return RecoveryRecord.from(recoveryRepository.save(recovery));
    }

    @Transactional(readOnly = true)
    public List<RecoveryRecord> pending(RepositoryContext context) {
        RepositoryContext selected = Objects.requireNonNull(context, "context");
        return recoveryRepository
                .findByRepositoryIdAndWorkspaceScopeKeyAndBranchAndStatusOrderByIdAsc(
                        selected.repositoryId(),
                        RelationDecisionProjection.scopeKeyFor(
                                selected.workspaceId()),
                        selected.branch(),
                        RecoveryStatus.PENDING)
                .stream()
                .map(RecoveryRecord::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public long pendingCount(RepositoryContext context) {
        RepositoryContext selected = Objects.requireNonNull(context, "context");
        return recoveryRepository
                .countByRepositoryIdAndWorkspaceScopeKeyAndBranchAndStatus(
                        selected.repositoryId(),
                        RelationDecisionProjection.scopeKeyFor(
                                selected.workspaceId()),
                        selected.branch(),
                        RecoveryStatus.PENDING);
    }

    /**
     * Completes pending records only when the rebuilt commit is the same commit
     * or demonstrably descends from the failed authoritative commit.
     */
    @Transactional
    public ReconciliationResult reconcileAfterRebuild(
            RepositoryContext context,
            String rebuiltCommitId) {
        RepositoryContext selected = requireMutable(context);
        String rebuilt = requireCommitId(rebuiltCommitId);
        String scopeKey = RelationDecisionProjection.scopeKeyFor(
                selected.workspaceId());
        List<RelationProjectionRecovery> pending = recoveryRepository
                .findStatusForUpdate(
                        selected.repositoryId(),
                        scopeKey,
                        selected.branch(),
                        RecoveryStatus.PENDING);
        if (pending.isEmpty()) {
            return new ReconciliationResult(rebuilt, 0, 0, 0);
        }

        DslGitRepository dslRepository = gitRepositoryFactory
                .resolveRepository(selected);
        int recovered = 0;
        int superseded = 0;
        try (RevWalk walk = new RevWalk(dslRepository.getGitRepository())) {
            RevCommit rebuiltCommit = walk.parseCommit(ObjectId.fromString(rebuilt));
            for (RelationProjectionRecovery recovery : pending) {
                RecoveryStatus completion = completion(
                        walk,
                        rebuiltCommit,
                        recovery.getAuthoritativeCommitId());
                if (completion == null) {
                    continue;
                }
                recovery.complete(completion);
                recoveryRepository.save(recovery);
                if (completion == RecoveryStatus.RECOVERED) {
                    recovered++;
                } else {
                    superseded++;
                }
            }
        } catch (IOException error) {
            throw new ProjectionRecoveryReconciliationException(
                    "Unable to reconcile relation projection recovery at "
                            + rebuilt,
                    error);
        }
        return new ReconciliationResult(
                rebuilt,
                recovered,
                superseded,
                pending.size() - recovered - superseded);
    }

    private static RecoveryStatus completion(
            RevWalk walk,
            RevCommit rebuiltCommit,
            String pendingCommitId) {
        try {
            RevCommit pendingCommit = walk.parseCommit(
                    ObjectId.fromString(pendingCommitId));
            if (pendingCommit.equals(rebuiltCommit)) {
                return RecoveryStatus.RECOVERED;
            }
            return walk.isMergedInto(pendingCommit, rebuiltCommit)
                    ? RecoveryStatus.SUPERSEDED
                    : null;
        } catch (IOException | IllegalArgumentException error) {
            return null;
        }
    }

    private static RepositoryContext requireMutable(RepositoryContext context) {
        RepositoryContext selected = Objects.requireNonNull(context, "context");
        if (selected.scope() == RepositoryScope.CENTRAL_READ) {
            throw new IllegalArgumentException(
                    "Projection recovery reconciliation requires a writable context");
        }
        return selected;
    }

    private static String requireCommitId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "rebuiltCommitId must not be blank");
        }
        return ObjectId.fromString(value.strip()).name()
                .toLowerCase(Locale.ROOT);
    }

    public record RecoveryRecord(
            Long id,
            String repositoryId,
            String workspaceId,
            String branch,
            String previousHeadCommit,
            String authoritativeCommitId,
            String causationId,
            RecoveryStatus status,
            int attemptCount,
            String failureType,
            String failureMessage,
            java.time.Instant firstObservedAt,
            java.time.Instant lastObservedAt,
            java.time.Instant completedAt) {

        static RecoveryRecord from(RelationProjectionRecovery recovery) {
            return new RecoveryRecord(
                    recovery.getId(),
                    recovery.getRepositoryId(),
                    recovery.getWorkspaceId(),
                    recovery.getBranch(),
                    recovery.getPreviousHeadCommit(),
                    recovery.getAuthoritativeCommitId(),
                    recovery.getCausationId(),
                    recovery.getStatus(),
                    recovery.getAttemptCount(),
                    recovery.getFailureType(),
                    recovery.getFailureMessage(),
                    recovery.getFirstObservedAt(),
                    recovery.getLastObservedAt(),
                    recovery.getCompletedAt());
        }
    }

    public record ReconciliationResult(
            String rebuiltCommitId,
            int recoveredCount,
            int supersededCount,
            int remainingPendingCount) {
    }

    public static final class ProjectionRecoveryReconciliationException
            extends IllegalStateException {
        public ProjectionRecoveryReconciliationException(
                String message,
                Throwable cause) {
            super(message, cause);
        }
    }
}
