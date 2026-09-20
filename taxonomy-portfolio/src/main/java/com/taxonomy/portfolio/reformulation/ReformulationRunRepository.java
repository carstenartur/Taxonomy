package com.taxonomy.portfolio.reformulation;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
public interface ReformulationRunRepository extends JpaRepository<ReformulationRun,String> {
    List<ReformulationRun> findByProposalIdAndScopeKey(String proposalId,String scopeKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReformulationRun r where r.id=:id and r.proposalId=:proposalId and r.scopeKey=:scopeKey")
    Optional<ReformulationRun> lockScoped(String id,String proposalId,String scopeKey);
}
