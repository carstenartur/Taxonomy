package com.taxonomy.versioning.service;

import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Isolates hypothesis bookkeeping from the non-transactional Git authority
 * boundary. Every lookup and transition is scoped to one exact repository and
 * workspace; a database transaction never encloses the JGit command itself.
 */
@Service
public class HypothesisReviewStateStore {

    private final RelationHypothesisRepository repository;
    private final EntityManager entityManager;

    public HypothesisReviewStateStore(
            RelationHypothesisRepository repository,
            EntityManager entityManager) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public HypothesisSnapshot require(
            Long hypothesisId,
            RepositoryContext context) {
        RepositoryContext tenant = requireWritableContext(context);
        RelationHypothesis hypothesis = repository
                .findByIdInRepositoryWorkspace(
                        tenant.repositoryId(),
                        Objects.requireNonNull(hypothesisId, "hypothesisId"),
                        tenant.workspaceId())
                .orElseThrow(() -> notFound(hypothesisId));
        return HypothesisSnapshot.from(hypothesis);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RelationHypothesis transition(
            Long hypothesisId,
            RepositoryContext context,
            HypothesisStatus expectedStatus,
            HypothesisStatus targetStatus) {
        RepositoryContext tenant = requireWritableContext(context);
        RelationHypothesis hypothesis = repository
                .findByIdInRepositoryWorkspace(
                        tenant.repositoryId(),
                        Objects.requireNonNull(hypothesisId, "hypothesisId"),
                        tenant.workspaceId())
                .orElseThrow(() -> notFound(hypothesisId));
        entityManager.lock(hypothesis, LockModeType.PESSIMISTIC_WRITE);
        if (hypothesis.getStatus() != expectedStatus) {
            throw new HypothesisReviewConflictException(
                    hypothesisId,
                    expectedStatus,
                    hypothesis.getStatus());
        }
        hypothesis.setStatus(Objects.requireNonNull(targetStatus, "targetStatus"));
        return repository.saveAndFlush(hypothesis);
    }

    static RepositoryContext requireWritableContext(RepositoryContext context) {
        RepositoryContext tenant = Objects.requireNonNull(context, "context");
        if (tenant.scope() == RepositoryScope.CENTRAL_READ) {
            throw new IllegalStateException(
                    "Hypothesis review requires a workspace, fork, or explicit central write context");
        }
        return tenant;
    }

    private static IllegalArgumentException notFound(Long hypothesisId) {
        return new IllegalArgumentException("Hypothesis not found: " + hypothesisId);
    }

    public record HypothesisSnapshot(
            Long id,
            String sourceCode,
            String targetCode,
            RelationType relationType,
            Double confidence,
            HypothesisStatus status) {

        public HypothesisSnapshot {
            id = Objects.requireNonNull(id, "id");
            sourceCode = requireText(sourceCode, "sourceCode");
            targetCode = requireText(targetCode, "targetCode");
            relationType = Objects.requireNonNull(relationType, "relationType");
            status = Objects.requireNonNull(status, "status");
        }

        static HypothesisSnapshot from(RelationHypothesis hypothesis) {
            return new HypothesisSnapshot(
                    hypothesis.getId(),
                    hypothesis.getSourceNodeId(),
                    hypothesis.getTargetNodeId(),
                    hypothesis.getRelationType(),
                    hypothesis.getConfidence(),
                    hypothesis.getStatus());
        }
    }

    public static final class HypothesisReviewConflictException
            extends IllegalStateException {
        private final Long hypothesisId;
        private final HypothesisStatus expectedStatus;
        private final HypothesisStatus actualStatus;

        public HypothesisReviewConflictException(
                Long hypothesisId,
                HypothesisStatus expectedStatus,
                HypothesisStatus actualStatus) {
            super("Hypothesis " + hypothesisId + " changed from "
                    + expectedStatus + " to " + actualStatus
                    + " before review bookkeeping completed");
            this.hypothesisId = hypothesisId;
            this.expectedStatus = expectedStatus;
            this.actualStatus = actualStatus;
        }

        public Long getHypothesisId() {
            return hypothesisId;
        }

        public HypothesisStatus getExpectedStatus() {
            return expectedStatus;
        }

        public HypothesisStatus getActualStatus() {
            return actualStatus;
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
