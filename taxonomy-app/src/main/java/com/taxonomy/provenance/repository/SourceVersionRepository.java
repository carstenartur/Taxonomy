package com.taxonomy.provenance.repository;

import com.taxonomy.provenance.model.SourceVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SourceVersionRepository extends JpaRepository<SourceVersion, Long> {

    List<SourceVersion> findBySourceArtifactId(Long sourceArtifactId);

    /** Serialize idempotent candidate confirmation for one source version. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from SourceVersion v "
            + "join fetch v.sourceArtifact where v.id = :id")
    Optional<SourceVersion> findByIdForUpdate(@Param("id") Long id);
}
