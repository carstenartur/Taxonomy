package com.taxonomy.catalog.repository;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.TaxonomyNodeDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaxonomyNodeRepository extends JpaRepository<TaxonomyNode, Long> , com.taxonomy.catalog.api.TaxonomyNodeLookup {

    Optional<TaxonomyNode> findByCode(String code);

    List<TaxonomyNode> findByCodeIn(Collection<String> codes);

    List<TaxonomyNode> findByParentIsNullOrderByCodeAsc();

    record TreeRow(Long parentId, TaxonomyNodeDto node) {}

    /** The persisted parent ID links rows. The DTO aliases select the public fields without fetching embeddings. */
    @Query("""
            select new com.taxonomy.catalog.repository.TaxonomyNodeRepository$TreeRow(
                n.parent.id, new com.taxonomy.dto.TaxonomyNodeDto(
                    n.id as id, n.code as code, n.uuid as uuid, n.nameEn as nameEn, n.nameDe as nameDe,
                    n.descriptionEn as descriptionEn, n.descriptionDe as descriptionDe, n.parentCode as parentCode,
                    n.taxonomyRoot as taxonomyRoot, n.level as level, n.dataset as dataset, n.externalId as externalId,
                    n.source as source, n.reference as reference, n.sortOrder as sortOrder, n.state as state))
            from TaxonomyNode n
            order by case when n.parent is null then n.code else n.nameEn end, n.code
            """)
    List<TreeRow> findAllInTreeOrder();

    List<TaxonomyNode> findByParentCodeOrderByNameEnAsc(String parentCode);

    List<TaxonomyNode> findByTaxonomyRootOrderByLevelAscNameEnAsc(String taxonomyRoot);
}
