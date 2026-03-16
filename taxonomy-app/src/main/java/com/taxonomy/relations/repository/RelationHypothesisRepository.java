package com.taxonomy.relations.repository;

import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.model.RelationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RelationHypothesisRepository extends JpaRepository<RelationHypothesis, Long> {

    List<RelationHypothesis> findByStatus(HypothesisStatus status);

    List<RelationHypothesis> findByAnalysisSessionId(String analysisSessionId);

    List<RelationHypothesis> findBySourceNodeIdAndTargetNodeIdAndRelationType(
            String sourceNodeId, String targetNodeId, RelationType relationType);

    List<RelationHypothesis> findByStatusIn(List<HypothesisStatus> statuses);
}
