package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.SystemRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** JPA repository for catalogued central architecture repositories. */
@Repository
public interface SystemRepositoryRepository extends JpaRepository<SystemRepository, Long> {

    Optional<SystemRepository> findByPrimaryRepoTrue();

    Optional<SystemRepository> findByRepositoryId(String repositoryId);

    Optional<SystemRepository> findBySlug(String slug);

    Optional<SystemRepository> findByStorageRepositoryName(String storageRepositoryName);

    List<SystemRepository> findByLifecycleStateOrderByDisplayNameAsc(
            RepositoryLifecycleState lifecycleState);
}
