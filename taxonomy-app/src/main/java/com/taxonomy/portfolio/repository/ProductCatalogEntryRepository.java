package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ProductCatalogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductCatalogEntryRepository extends JpaRepository<ProductCatalogEntry, Long> {

    List<ProductCatalogEntry> findByScopeKeyOrderByManufacturerAscProductNameAsc(String scopeKey);

    Optional<ProductCatalogEntry> findByIdAndScopeKey(Long id, String scopeKey);

    Optional<ProductCatalogEntry> findByScopeKeyAndProductKeyIgnoreCase(String scopeKey, String productKey);
}
