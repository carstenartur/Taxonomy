package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ProductCatalogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductCatalogEntryRepository extends JpaRepository<ProductCatalogEntry, Long> {

    List<ProductCatalogEntry> findByScopeKeyOrderByManufacturerAscProductNameAsc(String scopeKey);

    Optional<ProductCatalogEntry> findByIdAndScopeKey(Long id, String scopeKey);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT)
    @org.springframework.data.jpa.repository.Query("select p from ProductCatalogEntry p where p.id=:id and p.scopeKey=:scope")
    Optional<ProductCatalogEntry> findByIdAndScopeKeyForUpdate(@org.springframework.data.repository.query.Param("id") Long id,
                                                            @org.springframework.data.repository.query.Param("scope") String scope);

    Optional<ProductCatalogEntry> findByScopeKeyAndProductKeyIgnoreCase(String scopeKey, String productKey);
}
