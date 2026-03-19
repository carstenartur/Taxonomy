package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.SystemRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JPA repository for {@link SystemRepository} entities.
 */
@Repository
public interface SystemRepositoryRepository extends JpaRepository<SystemRepository, Long> {

    Optional<SystemRepository> findByPrimaryRepoTrue();

    Optional<SystemRepository> findByRepositoryId(String repositoryId);
}
