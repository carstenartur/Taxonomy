package com.nato.taxonomy.repository;

import com.nato.taxonomy.model.RelationEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RelationEvidenceRepository extends JpaRepository<RelationEvidence, Long> {

    List<RelationEvidence> findByHypothesisId(Long hypothesisId);
}
