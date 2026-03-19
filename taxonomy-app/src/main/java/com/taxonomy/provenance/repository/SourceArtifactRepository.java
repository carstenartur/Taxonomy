package com.taxonomy.provenance.repository;

import com.taxonomy.model.SourceType;
import com.taxonomy.provenance.model.SourceArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SourceArtifactRepository extends JpaRepository<SourceArtifact, Long> {

    List<SourceArtifact> findBySourceType(SourceType sourceType);

    Optional<SourceArtifact> findByCanonicalIdentifier(String canonicalIdentifier);
}
