package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.SolutionDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SolutionDefinitionRepository extends JpaRepository<SolutionDefinition, Long> {

    List<SolutionDefinition> findByScopeKeyOrderByTitleAsc(String scopeKey);

    Optional<SolutionDefinition> findByIdAndScopeKey(Long id, String scopeKey);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT)
    @org.springframework.data.jpa.repository.Query("select s from SolutionDefinition s where s.id=:id and s.scopeKey=:scope")
    Optional<SolutionDefinition> findByIdAndScopeKeyForUpdate(@org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("scope") String scope);

    Optional<SolutionDefinition> findByScopeKeyAndSolutionKeyIgnoreCase(String scopeKey, String solutionKey);
}
