package com.taxonomy.relations.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.BranchProjectionSourceException;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.RebuildResult;
import com.taxonomy.relations.service.RelationProjectionRecoveryService.ReconciliationResult;
import com.taxonomy.relations.service.RelationProjectionRecoveryService.RecoveryRecord;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Authorized operational boundary for branch projection readiness and rebuilds. */
@Service
public class RelationProjectionOperationsService {

    private final DslGitRepositoryFactory gitRepositoryFactory;
    private final RelationBranchProjectionReadinessService readinessService;
    private final RelationBranchProjectionRebuildService rebuildService;
    private final RelationProjectionRecoveryService recoveryService;
    private final ExpectedHeadDslCommitter expectedHeadVerifier;

    @Autowired
    public RelationProjectionOperationsService(
            DslGitRepositoryFactory gitRepositoryFactory,
            RelationBranchProjectionReadinessService readinessService,
            RelationBranchProjectionRebuildService rebuildService,
            RelationProjectionRecoveryService recoveryService) {
        this(
                gitRepositoryFactory,
                readinessService,
                rebuildService,
                recoveryService,
                new ExpectedHeadDslCommitter());
    }

    RelationProjectionOperationsService(
            DslGitRepositoryFactory gitRepositoryFactory,
            RelationBranchProjectionReadinessService readinessService,
            RelationBranchProjectionRebuildService rebuildService,
            RelationProjectionRecoveryService recoveryService,
            ExpectedHeadDslCommitter expectedHeadVerifier) {
        this.gitRepositoryFactory = Objects.requireNonNull(
                gitRepositoryFactory, "gitRepositoryFactory");
        this.readinessService = Objects.requireNonNull(
                readinessService, "readinessService");
        this.rebuildService = Objects.requireNonNull(
                rebuildService, "rebuildService");
        this.recoveryService = Objects.requireNonNull(
                recoveryService, "recoveryService");
        this.expectedHeadVerifier = Objects.requireNonNull(
                expectedHeadVerifier, "expectedHeadVerifier");
    }

    public ProjectionStatus inspect(RepositoryContext context) {
        RepositoryContext selected = Objects.requireNonNull(context, "context");
        return new ProjectionStatus(
                readinessService.inspect(selected),
                recoveryService.pending(selected));
    }

    /**
     * Rebuilds only after proving the caller's exact branch head. A benign
     * rebuild of a concurrently newer head is reported as a precondition
     * conflict rather than silently satisfying the stale request.
     */
    public RebuildOperation rebuild(
            RepositoryContext context,
            String expectedHeadCommit) throws IOException {
        RepositoryContext selected = requireMutable(context);
        DslGitRepository repository = gitRepositoryFactory
                .resolveRepository(selected);
        String verifiedHead = expectedHeadVerifier.verifyExpectedHead(
                repository,
                selected.branch(),
                expectedHeadCommit);
        if (verifiedHead == null) {
            throw new BranchProjectionSourceException(
                    "Cannot rebuild relation projection for an absent branch");
        }

        RebuildResult rebuilt = rebuildService.rebuild(selected);
        if (!verifiedHead.equals(rebuilt.authoritativeCommitId())) {
            throw new RebuildHeadConflictException(
                    verifiedHead,
                    rebuilt.authoritativeCommitId());
        }

        Readiness readiness = readinessService.inspect(selected);
        if (!rebuilt.authoritativeCommitId().equals(
                readiness.currentHeadCommit())) {
            throw new RebuildHeadConflictException(
                    rebuilt.authoritativeCommitId(),
                    readiness.currentHeadCommit());
        }
        boolean verifiedProjection = readiness.state() == ReadinessState.READY
                && rebuilt.authoritativeCommitId().equals(
                        readiness.projectedCommit())
                && rebuilt.relationCount() == readiness.rows().size();
        if (!verifiedProjection) {
            throw new RebuildVerificationException(rebuilt, readiness);
        }

        ReconciliationResult reconciliation;
        try {
            reconciliation = recoveryService.reconcileAfterRebuild(
                    selected, rebuilt.authoritativeCommitId());
        } catch (RuntimeException error) {
            throw new RecoveryReconciliationPendingException(rebuilt, error);
        }
        return new RebuildOperation(rebuilt, reconciliation, readiness);
    }

    private static RepositoryContext requireMutable(RepositoryContext context) {
        RepositoryContext selected = Objects.requireNonNull(context, "context");
        if (selected.scope() == RepositoryScope.CENTRAL_READ) {
            throw new IllegalArgumentException(
                    "Projection rebuild requires a writable repository context");
        }
        return selected;
    }

    public record ProjectionStatus(
            Readiness readiness,
            List<RecoveryRecord> pendingRecoveries) {
        public ProjectionStatus {
            readiness = Objects.requireNonNull(readiness, "readiness");
            pendingRecoveries = List.copyOf(Objects.requireNonNull(
                    pendingRecoveries, "pendingRecoveries"));
        }
    }

    public record RebuildOperation(
            RebuildResult rebuild,
            ReconciliationResult reconciliation,
            Readiness readiness) {
        public RebuildOperation {
            rebuild = Objects.requireNonNull(rebuild, "rebuild");
            reconciliation = Objects.requireNonNull(
                    reconciliation, "reconciliation");
            readiness = Objects.requireNonNull(readiness, "readiness");
        }
    }

    public static final class RebuildHeadConflictException
            extends IllegalStateException {
        private final String expectedHeadCommit;
        private final String actualHeadCommit;

        public RebuildHeadConflictException(
                String expectedHeadCommit,
                String actualHeadCommit) {
            super("Relation projection rebuild expected "
                    + expectedHeadCommit + " but observed " + actualHeadCommit);
            this.expectedHeadCommit = expectedHeadCommit;
            this.actualHeadCommit = actualHeadCommit;
        }

        public String getExpectedHeadCommit() {
            return expectedHeadCommit;
        }

        public String getActualHeadCommit() {
            return actualHeadCommit;
        }
    }

    public static final class RebuildVerificationException
            extends IllegalStateException {
        private final RebuildResult rebuild;
        private final Readiness readiness;

        public RebuildVerificationException(
                RebuildResult rebuild,
                Readiness readiness) {
            super("Relation projection rebuild at "
                    + rebuild.authoritativeCommitId()
                    + " did not produce a verified READY projection: "
                    + readiness.state());
            this.rebuild = Objects.requireNonNull(rebuild, "rebuild");
            this.readiness = Objects.requireNonNull(readiness, "readiness");
        }

        public RebuildResult getRebuild() {
            return rebuild;
        }

        public Readiness getReadiness() {
            return readiness;
        }
    }

    public static final class RecoveryReconciliationPendingException
            extends IllegalStateException {
        private final RebuildResult rebuild;

        public RecoveryReconciliationPendingException(
                RebuildResult rebuild,
                Throwable cause) {
            super("Relation projection rebuilt at "
                    + rebuild.authoritativeCommitId()
                    + ", but recovery reconciliation is pending", cause);
            this.rebuild = Objects.requireNonNull(rebuild, "rebuild");
        }

        public RebuildResult getRebuild() {
            return rebuild;
        }
    }
}
