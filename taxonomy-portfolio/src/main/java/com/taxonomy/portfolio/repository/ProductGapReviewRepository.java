package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ProductGapReview;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductGapReviewRepository extends JpaRepository<ProductGapReview, String> {
    List<ProductGapReview> findByProjectIdAndScopeKeyOrderByGapKeyAscRevisionAsc(Long projectId, String scopeKey);
    List<ProductGapReview> findByProjectIdAndScopeKeyAndGapKeyOrderByRevisionAsc(Long projectId, String scopeKey, String gapKey);
}
