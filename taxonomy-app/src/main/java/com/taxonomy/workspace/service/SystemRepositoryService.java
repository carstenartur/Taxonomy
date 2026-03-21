package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Manages the system-owned central repository configuration.
 *
 * <p>On application startup, this service ensures that exactly one primary
 * system repository record exists. The system repository defines the shared
 * integration branch name and topology mode used by all user workspaces.
 *
 * <p>Other services (e.g. {@link SyncIntegrationService}) use this service
 * to look up the shared branch name instead of hardcoding it.
 */
@Service
public class SystemRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(SystemRepositoryService.class);

    private final SystemRepositoryRepository repository;

    public SystemRepositoryService(SystemRepositoryRepository repository) {
        this.repository = repository;
    }

    /**
     * Ensure a primary system repository record exists on startup.
     *
     * <p>If no primary repository is found, a new one is created with
     * default values (INTERNAL_SHARED topology, "draft" default branch).
     */
    @PostConstruct
    public void ensureSystemRepository() {
        try {
            if (repository.findByPrimaryRepoTrue().isEmpty()) {
                SystemRepository sysRepo = new SystemRepository();
                sysRepo.setRepositoryId(UUID.randomUUID().toString());
                sysRepo.setDisplayName("Shared Architecture Repository");
                sysRepo.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
                sysRepo.setDefaultBranch("draft");
                sysRepo.setPrimaryRepo(true);
                sysRepo.setCreatedAt(Instant.now());
                repository.save(sysRepo);
                log.info("Created primary system repository (topology=INTERNAL_SHARED, branch=draft)");
            }
        } catch (Exception e) {
            log.warn("Could not ensure system repository: {}", e.getMessage());
        }
    }

    /**
     * Get the primary system repository.
     *
     * @return the primary system repository entity
     * @throws IllegalStateException if no primary repository is configured
     */
    public SystemRepository getPrimaryRepository() {
        return repository.findByPrimaryRepoTrue()
                .orElseThrow(() -> new IllegalStateException("No primary system repository configured"));
    }

    /**
     * Get the shared branch name from the primary system repository.
     *
     * <p>Falls back to {@code "draft"} if the system repository is not yet
     * available (e.g. during early startup or test scenarios).
     *
     * @return the shared branch name
     */
    public String getSharedBranch() {
        try {
            return getPrimaryRepository().getDefaultBranch();
        } catch (Exception e) {
            log.debug("System repository not available, falling back to 'draft': {}", e.getMessage());
            return "draft";
        }
    }

    /**
     * Persist updates to a system repository entity.
     *
     * @param sysRepo the entity to save
     * @return the saved entity
     */
    public SystemRepository save(SystemRepository sysRepo) {
        return repository.save(sysRepo);
    }
}
