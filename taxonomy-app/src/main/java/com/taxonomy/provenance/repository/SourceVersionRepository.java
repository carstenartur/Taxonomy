package com.taxonomy.provenance.repository;

import com.taxonomy.provenance.model.SourceVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SourceVersionRepository extends JpaRepository<SourceVersion, Long> {

    List<SourceVersion> findBySourceArtifactId(Long sourceArtifactId);
}
