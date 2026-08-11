package com.taxonomy.relations.repository;

import com.taxonomy.relations.model.RelationProposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Narrow persistence boundary used only after a Git-authoritative proposal decision. */
public interface RelationProposalReviewRepository
        extends Repository<RelationProposal, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p FROM RelationProposal p
            JOIN FETCH p.sourceNode
            JOIN FETCH p.targetNode
            WHERE p.repositoryId = :repositoryId
              AND p.id = :id
              AND ((:workspaceId IS NULL AND p.workspaceId IS NULL)
                   OR p.workspaceId = :workspaceId)
            """)
    Optional<RelationProposal> findExactForUpdate(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId,
            @Param("id") Long id);

    RelationProposal save(RelationProposal proposal);

    void flush();
}
