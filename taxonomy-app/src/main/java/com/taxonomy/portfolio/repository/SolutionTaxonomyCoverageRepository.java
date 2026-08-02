package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.SolutionTaxonomyCoverage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SolutionTaxonomyCoverageRepository
        extends JpaRepository<SolutionTaxonomyCoverage, Long> {

    List<SolutionTaxonomyCoverage> findBySolutionIdOrderByNodeCodeAsc(Long solutionId);

    List<SolutionTaxonomyCoverage> findByNodeCode(String nodeCode);

    Optional<SolutionTaxonomyCoverage> findBySolutionIdAndNodeCode(Long solutionId, String nodeCode);
}
