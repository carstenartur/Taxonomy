package com.taxonomy.security.repository;

import com.taxonomy.security.model.AppUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    /**
     * Serializes operations that may remove local administrative access. The
     * user rows are locked in stable ID order so concurrent disable/role-change
     * transactions re-evaluate the invariant after the preceding commit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select distinct user
            from AppUser user
            join user.roles role
            where user.enabled = true
              and role.name = 'ROLE_ADMIN'
            order by user.id
            """)
    List<AppUser> lockEnabledAdministrators();
}
