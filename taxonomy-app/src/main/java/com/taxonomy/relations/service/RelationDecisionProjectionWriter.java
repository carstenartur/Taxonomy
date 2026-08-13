package com.taxonomy.relations.service;

import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionConflictException;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionOutcome;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionRequest;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Transactional writer for one already Git-verified incremental projection state. */
@Service
public class RelationDecisionProjectionWriter {

    private final RelationDecisionProjectionRepository projectionRepository;
    private final RelationDecisionProjectionCheckpointRepository checkpointRepository;

    @Autowired
    public RelationDecisionProjectionWriter(
            RelationDecisionProjectionRepository projectionRepository,
            RelationDecisionProjectionCheckpointRepository checkpointRepository) {
        this.projectionRepository = Objects.requireNonNull(
                projectionRepository, "projectionRepository");
        this.checkpointRepository = Objects.requireNonNull(
                checkpointRepository, "checkpointRepository");
    }

    /** Compatibility constructor for focused pre-checkpoint unit tests in this package. */
    RelationDecisionProjectionWriter(
            RelationDecisionProjectionRepository projectionRepository) {
        this.projectionRepository = Objects.requireNonNull(
                projectionRepository, "projectionRepository");
        this.checkpointRepository = null;
    }

    @Transactional
    public ProjectionResult write(ProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        ProjectionRequest effectiveRequest = effectiveState(request);
        String workspaceScopeKey = RelationDecisionProjection.scopeKeyFor(
                effectiveRequest.workspaceId());

        // An incremental command proves only one relation identity. Even when it
        // is a semantic no-op, it must not leave a previous full-branch checkpoint
        // available as an assurance that every row was rebuilt at this commit.
        // Runtime failures roll this deletion back with the relation write.
        if (checkpointRepository != null) {
            checkpointRepository.deleteExact(
                    effectiveRequest.repositoryId(),
                    workspaceScopeKey,
                    effectiveRequest.branch());
        }

        RelationDecisionProjection existing = projectionRepository
                .findExactForUpdate(
                        effectiveRequest.repositoryId(),
                        workspaceScopeKey,
                        effectiveRequest.branch(),
                        effectiveRequest.sourceCode(),
                        effectiveRequest.relationType(),
                        effectiveRequest.targetCode())
                .orElse(null);

        if (existing == null) {
            RelationDecisionProjection created = new RelationDecisionProjection();
            applyIdentity(created, effectiveRequest);
            applyState(created, effectiveRequest);
            applyAuthority(created, effectiveRequest);
            projectionRepository.save(created);
            return result(ProjectionOutcome.CREATED, effectiveRequest);
        }

        if (effectiveRequest.authoritativeCommitId().equals(
                existing.getAuthoritativeCommitId())) {
            if (!sameState(existing, effectiveRequest)) {
                throw new ProjectionConflictException(
                        "Authoritative commit "
                                + effectiveRequest.authoritativeCommitId()
                                + " is already projected with different relation state");
            }
            // Multiple commands may legitimately prove the same semantic no-op
            // at one unchanged Git head. Their causation IDs identify command
            // attempts, not different authoritative relation states. A complete
            // rebuild also records its own rebuild:<commit> causation ID, so
            // requiring equality here would reject every later unchanged command.
            return result(ProjectionOutcome.REPLAYED, effectiveRequest);
        }

        applyState(existing, effectiveRequest);
        applyAuthority(existing, effectiveRequest);
        projectionRepository.save(existing);
        return result(ProjectionOutcome.UPDATED, effectiveRequest);
    }

    private static ProjectionRequest effectiveState(ProjectionRequest request) {
        boolean relationPresent = request.relationPresent()
                && RelationDecisionStatusPolicy.isRelationPresent(request.status());
        if (relationPresent == request.relationPresent()) {
            return request;
        }
        return new ProjectionRequest(
                request.repositoryId(),
                request.workspaceId(),
                request.branch(),
                request.sourceCode(),
                request.relationType(),
                request.targetCode(),
                false,
                request.status(),
                request.confidence(),
                request.provenance(),
                request.authoritativeCommitId(),
                request.causationId());
    }

    private static void applyIdentity(
            RelationDecisionProjection projection,
            ProjectionRequest request) {
        projection.setRepositoryId(request.repositoryId());
        projection.setWorkspaceId(request.workspaceId());
        projection.setBranch(request.branch());
        projection.setSourceCode(request.sourceCode());
        projection.setRelationType(request.relationType());
        projection.setTargetCode(request.targetCode());
    }

    private static void applyState(
            RelationDecisionProjection projection,
            ProjectionRequest request) {
        projection.setRelationPresent(request.relationPresent());
        projection.setStatus(request.status());
        projection.setConfidence(request.confidence());
        projection.setProvenance(request.provenance());
    }

    private static void applyAuthority(
            RelationDecisionProjection projection,
            ProjectionRequest request) {
        projection.setAuthoritativeCommitId(
                request.authoritativeCommitId());
        projection.setCausationId(request.causationId());
    }

    private static boolean sameState(
            RelationDecisionProjection projection,
            ProjectionRequest request) {
        return projection.isRelationPresent() == request.relationPresent()
                && Objects.equals(projection.getStatus(), request.status())
                && Objects.equals(
                        projection.getConfidence(), request.confidence())
                && Objects.equals(
                        projection.getProvenance(), request.provenance());
    }

    private static ProjectionResult result(
            ProjectionOutcome outcome,
            ProjectionRequest request) {
        return new ProjectionResult(
                outcome,
                request.authoritativeCommitId(),
                request.relationPresent());
    }
}
