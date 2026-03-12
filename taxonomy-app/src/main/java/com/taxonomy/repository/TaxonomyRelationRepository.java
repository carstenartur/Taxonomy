package com.taxonomy.repository;

import com.taxonomy.model.RelationType;
import com.taxonomy.model.TaxonomyRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaxonomyRelationRepository extends JpaRepository<TaxonomyRelation, Long> {

    List<TaxonomyRelation> findBySourceNodeCode(String code);

    List<TaxonomyRelation> findByTargetNodeCode(String code);

    List<TaxonomyRelation> findByRelationType(RelationType relationType);

    List<TaxonomyRelation> findBySourceNodeCodeOrTargetNodeCode(String sourceCode, String targetCode);

    List<TaxonomyRelation> findByRelationTypeIn(List<RelationType> relationTypes);

    List<TaxonomyRelation> findBySourceNodeCodeAndRelationTypeIn(String sourceCode, List<RelationType> types);

    List<TaxonomyRelation> findByTargetNodeCodeAndRelationTypeIn(String targetCode, List<RelationType> types);

    List<TaxonomyRelation> findBySourceNodeCodeAndTargetNodeCodeAndRelationType(
            String sourceCode, String targetCode, RelationType relationType);
}
