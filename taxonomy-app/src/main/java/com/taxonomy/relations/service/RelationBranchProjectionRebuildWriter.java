package com.taxonomy.relations.service;

import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.model.RelationDecisionProjectionCheckpoint;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.RebuildResult;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.RelationSnapshot;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Transactional replacement boundary for one fully validated branch projection. */
@Service
public class RelationBranchProjectionRebuildWriter {

    private final RelationDecisionProjectionRepository projectionRepository;
    private final RelationDecisionProjectionCheckpointRepository checkpointRepository;

    public RelationBranchProjectionRebuildWriter(
            RelationDecisionProjectionRepository projectionRepository,
            RelationDecisionProjectionCheckpointRepository checkpointRepository) {
        this.projectionRepository = Objects.requireNonNull(
                projectionRepository, "projectionRepository");
        this.checkpointRepository = Objects.requireNonNull(
                checkpointRepository, "checkpointRepository");
    }

    /**
     * Replaces the exact repository/workspace/branch rows and checkpoint in one
     * transaction. Failure leaves the previously complete projection untouched.
     */
    @Transactional
    public RebuildResult replace(
            RepositoryContext context,
            String authoritativeCommitId,
            List<RelationSnapshot> relations) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(authoritativeCommitId, "authoritativeCommitId");
        List<RelationSnapshot> snapshots = List.copyOf(
                Objects.requireNonNull(relations, "relations"));
        String workspaceScopeKey = RelationDecisionProjection.scopeKeyFor(
                context.workspaceId());

        RelationDecisionProjectionCheckpoint checkpoint = checkpointRepository
                .findExactForUpdate(
                        context.repositoryId(),
                        workspaceScopeKey,
                        context.branch())
                .orElseGet(RelationDecisionProjectionCheckpoint::new);

        projectionRepository.deleteExactBranch(
                context.repositoryId(),
                workspaceScopeKey,
                context.branch());
        projectionRepository.flush();

        List<RelationDecisionProjection> replacements = snapshots.stream()
                .sorted(Comparator
                        .comparing(RelationSnapshot::sourceCode)
                        .thenComparing(snapshot -> snapshot.relationType().name())
                        .thenComparing(RelationSnapshot::targetCode))
                .map(snapshot -> projection(
                        context,
                        authoritativeCommitId,
                        snapshot))
                .toList();
        projectionRepository.saveAll(replacements);
        projectionRepository.flush();

        checkpoint.setRepositoryId(context.repositoryId());
        checkpoint.setWorkspaceId(context.workspaceId());
        checkpoint.setBranch(context.branch());
        checkpoint.setAuthoritativeCommitId(authoritativeCommitId);
        checkpoint.setRelationCount(replacements.size());
        checkpointRepository.saveAndFlush(checkpoint);

        return new RebuildResult(
                context.repositoryId(),
                context.workspaceId(),
                context.branch(),
                authoritativeCommitId,
                replacements.size());
    }

    private static RelationDecisionProjection projection(
            RepositoryContext context,
            String authoritativeCommitId,
            RelationSnapshot snapshot) {
        RelationDecisionProjection projection = new RelationDecisionProjection();
        projection.setRepositoryId(context.repositoryId());
        projection.setWorkspaceId(context.workspaceId());
        projection.setBranch(context.branch());
        projection.setSourceCode(snapshot.sourceCode());
        projection.setRelationType(snapshot.relationType());
        projection.setTargetCode(snapshot.targetCode());
        projection.setRelationPresent(true);
        projection.setStatus(snapshot.status());
        projection.setConfidence(snapshot.confidence());
        projection.setProvenance(snapshot.provenance());
        projection.setAuthoritativeCommitId(authoritativeCommitId);
        projection.setCausationId("rebuild:" + authoritativeCommitId);
        return projection;
    }
}
