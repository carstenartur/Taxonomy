package com.taxonomy.relations.repository;

import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.model.RelationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RelationHypothesisRepository extends JpaRepository<RelationHypothesis, Long> {

    List<RelationHypothesis> findByStatus(HypothesisStatus status);

    List<RelationHypothesis> findByAnalysisSessionId(String analysisSessionId);

    List<RelationHypothesis> findBySourceNodeIdAndTargetNodeIdAndRelationType(
            String sourceNodeId, String targetNodeId, RelationType relationType);

    List<RelationHypothesis> findByStatusIn(List<HypothesisStatus> statuses);

    // ── Workspace-scoped queries ─────────────────────────────────────

    List<RelationHypothesis> findByWorkspaceId(String workspaceId);

    @Query("SELECT h FROM RelationHypothesis h WHERE h.workspaceId = :wsId OR h.workspaceId IS NULL")
    List<RelationHypothesis> findByWorkspaceIdIsNullOrWorkspaceId(@Param("wsId") String workspaceId);

    @Query("SELECT h FROM RelationHypothesis h WHERE h.status = :status AND (h.workspaceId = :wsId OR h.workspaceId IS NULL)")
    List<RelationHypothesis> findByStatusAndWorkspace(@Param("status") HypothesisStatus status,
                                                       @Param("wsId") String workspaceId);
}
