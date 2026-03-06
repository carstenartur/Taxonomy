package com.nato.taxonomy.repository;

import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.model.TaxonomyRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaxonomyRelationRepository extends JpaRepository<TaxonomyRelation, Long> {

    List<TaxonomyRelation> findBySourceNodeCode(String code);

    List<TaxonomyRelation> findByTargetNodeCode(String code);

    List<TaxonomyRelation> findByRelationType(RelationType relationType);

    List<TaxonomyRelation> findBySourceNodeCodeOrTargetNodeCode(String sourceCode, String targetCode);
}
