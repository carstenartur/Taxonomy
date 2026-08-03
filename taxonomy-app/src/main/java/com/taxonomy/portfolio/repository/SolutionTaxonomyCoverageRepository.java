package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.SolutionTaxonomyCoverage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SolutionTaxonomyCoverageRepository
        extends JpaRepository<SolutionTaxonomyCoverage, Long> {

    List<SolutionTaxonomyCoverage> findBySolutionIdOrderByNodeCodeAsc(Long solutionId);

    List<SolutionTaxonomyCoverage> findBySolutionIdInOrderBySolutionIdAscNodeCodeAsc(
            Collection<Long> solutionIds);

    List<SolutionTaxonomyCoverage> findByNodeCode(String nodeCode);

    List<SolutionTaxonomyCoverage> findByNodeCodeIn(Collection<String> nodeCodes);

    Optional<SolutionTaxonomyCoverage> findBySolutionIdAndNodeCode(Long solutionId, String nodeCode);
}
