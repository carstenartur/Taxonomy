package com.taxonomy.relations.repository;

import com.taxonomy.relations.model.RelationEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RelationEvidenceRepository extends JpaRepository<RelationEvidence, Long> {

    List<RelationEvidence> findByHypothesisId(Long hypothesisId);
}
