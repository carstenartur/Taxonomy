package com.nato.taxonomy.repository;

import com.nato.taxonomy.model.TaxonomyNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaxonomyNodeRepository extends JpaRepository<TaxonomyNode, Long> {

    Optional<TaxonomyNode> findByCode(String code);

    List<TaxonomyNode> findByParentIsNullOrderByCodeAsc();

    List<TaxonomyNode> findByParentCodeOrderByNameAsc(String parentCode);

    List<TaxonomyNode> findByTaxonomyRootOrderByLevelAscNameAsc(String taxonomyRoot);
}
