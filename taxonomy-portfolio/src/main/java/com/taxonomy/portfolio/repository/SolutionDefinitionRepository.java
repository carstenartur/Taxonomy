package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.SolutionDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SolutionDefinitionRepository extends JpaRepository<SolutionDefinition, Long> {

    List<SolutionDefinition> findByScopeKeyOrderByTitleAsc(String scopeKey);

    Optional<SolutionDefinition> findByIdAndScopeKey(Long id, String scopeKey);

    Optional<SolutionDefinition> findByScopeKeyAndSolutionKeyIgnoreCase(String scopeKey, String solutionKey);
}
