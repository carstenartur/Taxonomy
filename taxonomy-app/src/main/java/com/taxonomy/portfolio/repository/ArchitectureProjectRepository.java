package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ArchitectureProject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArchitectureProjectRepository extends JpaRepository<ArchitectureProject, Long> {

    List<ArchitectureProject> findByScopeKeyOrderByUpdatedAtDesc(String scopeKey);

    Optional<ArchitectureProject> findByIdAndScopeKey(Long id, String scopeKey);

    Optional<ArchitectureProject> findByScopeKeyAndProjectKeyIgnoreCase(String scopeKey, String projectKey);

    boolean existsByScopeKeyAndProjectKeyIgnoreCase(String scopeKey, String projectKey);
}
