package com.taxonomy.composition.demo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductDemoRunRepository extends JpaRepository<ProductDemoRun, String> {

    Optional<ProductDemoRun> findByIdAndScopeKey(String id, String scopeKey);

    Optional<ProductDemoRun> findByProjectIdAndScopeKey(Long projectId, String scopeKey);

    List<ProductDemoRun> findByScopeKeyOrderByCreatedAtDesc(String scopeKey);

    /** Row lock held until commit: provider admission and closing a run serialize on it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from ProductDemoRun run where run.projectId = :projectId and run.scopeKey = :scopeKey")
    Optional<ProductDemoRun> lockByProjectIdAndScopeKey(@Param("projectId") Long projectId,
                                                        @Param("scopeKey") String scopeKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from ProductDemoRun run where run.id = :id and run.scopeKey = :scopeKey")
    Optional<ProductDemoRun> lockByIdAndScopeKey(@Param("id") String id, @Param("scopeKey") String scopeKey);
}
