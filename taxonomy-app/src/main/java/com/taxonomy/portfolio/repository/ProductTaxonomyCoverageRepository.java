package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ProductTaxonomyCoverage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductTaxonomyCoverageRepository
        extends JpaRepository<ProductTaxonomyCoverage, Long> {

    List<ProductTaxonomyCoverage> findByProductIdOrderByNodeCodeAsc(Long productId);

    List<ProductTaxonomyCoverage> findByProductIdInOrderByProductIdAscNodeCodeAsc(
            Collection<Long> productIds);

    List<ProductTaxonomyCoverage> findByNodeCode(String nodeCode);

    Optional<ProductTaxonomyCoverage> findByProductIdAndNodeCode(Long productId, String nodeCode);
}
