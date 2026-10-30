package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryOwnerType;
import com.taxonomy.workspace.model.SystemRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JPA repository for catalogued central architecture repositories. */
@Repository
public interface SystemRepositoryRepository extends JpaRepository<SystemRepository, Long> {

    org.springframework.data.domain.Slice<SystemRepository> findByLifecycleStateOrderByDisplayNameAsc(
            com.taxonomy.workspace.model.RepositoryLifecycleState state, org.springframework.data.domain.Pageable pageable);

    Optional<SystemRepository> findByPrimaryRepoTrue();

    Optional<SystemRepository> findByRepositoryId(String repositoryId);

    /** Touch only publication metadata; retain concurrent catalogue policy and creation state. */
    @Modifying
    @Query("update SystemRepository repository set repository.lastPushAt = :publishedAt, "
            + "repository.updatedAt = :publishedAt, repository.version = repository.version + 1 "
            + "where repository.repositoryId = :repositoryId")
    int recordLastPush(@Param("repositoryId") String repositoryId, @Param("publishedAt") Instant publishedAt);

    // HSQL MVCC does not retain a SELECT FOR UPDATE fence. An actual no-op write
    // holds the existing row without invalidating repository metadata loaded by Git.
    @Modifying
    @Query("update SystemRepository repository set repository.version = repository.version where repository.repositoryId = :repositoryId")
    int lockForMutation(@Param("repositoryId") String repositoryId);

    Optional<SystemRepository> findBySlug(String slug);

    /** Serialize a retained creation claim across application instances. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select repository from SystemRepository repository where repository.slug = :slug")
    Optional<SystemRepository> findBySlugForUpdate(@Param("slug") String slug);

    List<SystemRepository> findByOwnerTypeAndOwnerIdAndLifecycleStateInOrderByDisplayNameAsc(
            RepositoryOwnerType ownerType, String ownerId, Collection<RepositoryLifecycleState> states);

    Optional<SystemRepository> findByStorageRepositoryName(String storageRepositoryName);

    List<SystemRepository> findByLifecycleStateOrderByDisplayNameAsc(
            RepositoryLifecycleState lifecycleState);
}
