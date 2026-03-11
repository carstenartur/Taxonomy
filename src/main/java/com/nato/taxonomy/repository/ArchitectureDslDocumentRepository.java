package com.nato.taxonomy.repository;

import com.nato.taxonomy.model.ArchitectureDslDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ArchitectureDslDocumentRepository extends JpaRepository<ArchitectureDslDocument, Long> {

    Optional<ArchitectureDslDocument> findByPath(String path);

    List<ArchitectureDslDocument> findByBranch(String branch);

    List<ArchitectureDslDocument> findByNamespace(String namespace);
}
