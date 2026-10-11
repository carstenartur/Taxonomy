package com.taxonomy.portfolio.demo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductDemoRunRepository extends JpaRepository<ProductDemoRun, String> {

    Optional<ProductDemoRun> findByIdAndScopeKey(String id, String scopeKey);

    Optional<ProductDemoRun> findByProjectIdAndScopeKey(Long projectId, String scopeKey);

    Optional<ProductDemoRun> findByProjectId(Long projectId);

    List<ProductDemoRun> findByScopeKeyOrderByCreatedAtDesc(String scopeKey);
}
