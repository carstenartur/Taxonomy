package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ArchitectureProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface ArchitectureProjectRepository extends JpaRepository<ArchitectureProject, Long> {

    List<ArchitectureProject> findByScopeKeyOrderByUpdatedAtDesc(String scopeKey);

    Optional<ArchitectureProject> findByIdAndScopeKey(Long id, String scopeKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ArchitectureProject p where p.id=:id and p.scopeKey=:scope")
    Optional<ArchitectureProject> findByIdAndScopeKeyForUpdate(@Param("id") Long id, @Param("scope") String scope);

    Optional<ArchitectureProject> findByScopeKeyAndProjectKeyIgnoreCase(String scopeKey, String projectKey);

    boolean existsByScopeKeyAndProjectKeyIgnoreCase(String scopeKey, String projectKey);
}
