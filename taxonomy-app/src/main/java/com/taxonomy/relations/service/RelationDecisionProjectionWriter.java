package com.taxonomy.relations.service;

import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionConflictException;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionOutcome;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionRequest;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Transactional writer for one already Git-verified incremental projection state. */
@Service
public class RelationDecisionProjectionWriter {

    private final RelationDecisionProjectionRepository projectionRepository;
    private final RelationDecisionProjectionCheckpointRepository checkpointRepository;

    public RelationDecisionProjectionWriter(
            RelationDecisionProjectionRepository projectionRepository,
            RelationDecisionProjectionCheckpointRepository checkpointRepository) {
        this.projectionRepository = Objects.requireNonNull(
                projectionRepository, "projectionRepository");
        this.checkpointRepository = Objects.requireNonNull(
                checkpointRepository, "checkpointRepository");
    }

    @Transactional
    public ProjectionResult write(ProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        String workspaceScopeKey = RelationDecisionProjection.scopeKeyFor(
                request.workspaceId());

        // An incremental command proves only one relation identity. Even when it
        // is a semantic no-op, it must not leave a previous full-branch checkpoint
        // available as an assurance that every row was rebuilt at this commit.
        // Runtime failures roll this deletion back with the relation write.
        checkpointRepository.deleteExact(
                request.repositoryId(),
                workspaceScopeKey,
                request.branch());

        RelationDecisionProjection existing = projectionRepository
                .findExactForUpdate(
                        request.repositoryId(),
                        workspaceScopeKey,
                        request.branch(),
                        request.sourceCode(),
                        request.relationType(),
                        request.targetCode())
                .orElse(null);

        if (existing == null) {
            RelationDecisionProjection created = new RelationDecisionProjection();
            applyIdentity(created, request);
            applyState(created, request);
            applyAuthority(created, request);
            projectionRepository.save(created);
            return result(ProjectionOutcome.CREATED, request);
        }

        if (request.authoritativeCommitId().equals(
                existing.getAuthoritativeCommitId())) {
            if (!sameState(existing, request)
                    || !request.causationId().equals(
                            existing.getCausationId())) {
                throw new ProjectionConflictException(
                        "Authoritative commit "
                                + request.authoritativeCommitId()
                                + " is already projected with different relation state");
            }
            return result(ProjectionOutcome.REPLAYED, request);
        }

        applyState(existing, request);
        applyAuthority(existing, request);
        projectionRepository.save(existing);
        return result(ProjectionOutcome.UPDATED, request);
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
