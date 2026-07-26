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

/** Manages the system-owned central repository configuration. */
@Service
public class SystemRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(SystemRepositoryService.class);

    private final SystemRepositoryRepository repository;

    public SystemRepositoryService(SystemRepositoryRepository repository) {
        this.repository = repository;
    }

    /**
     * Ensure a primary repository exists and erase credentials persisted by
     * releases that stored external Git tokens in plaintext.
     */
    @PostConstruct
    public void ensureSystemRepository() {
        try {
            var primaryRepository = repository.findByPrimaryRepoTrue();
            if (primaryRepository.isEmpty()) {
                SystemRepository systemRepository = new SystemRepository();
                systemRepository.setRepositoryId(UUID.randomUUID().toString());
                systemRepository.setDisplayName("Shared Architecture Repository");
                systemRepository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
                systemRepository.setDefaultBranch("draft");
                systemRepository.setPrimaryRepo(true);
                systemRepository.setCreatedAt(Instant.now());
                repository.save(systemRepository);
                log.info("Created primary system repository "
                        + "(topology=INTERNAL_SHARED, branch=draft)");
                return;
            }

            SystemRepository existing = primaryRepository.get();
            if (existing.hasLegacyPlaintextCredential()) {
                existing.clearLegacyPlaintextCredential();
                repository.save(existing);
                log.warn("Removed a legacy plaintext external Git credential from the database. "
                        + "Configure TAXONOMY_EXTERNAL_GIT_TOKEN through deployment secret storage.");
            }
        } catch (Exception exception) {
            log.warn("Could not ensure system repository: {}", exception.getMessage());
        }
    }

    public SystemRepository getPrimaryRepository() {
        return repository.findByPrimaryRepoTrue()
                .orElseThrow(() -> new IllegalStateException(
                        "No primary system repository configured"));
    }

    /** Return the configured shared branch, falling back during early startup. */
    public String getSharedBranch() {
        try {
            return getPrimaryRepository().getDefaultBranch();
        } catch (Exception exception) {
            log.debug("System repository not available, falling back to 'draft': {}",
                    exception.getMessage());
            return "draft";
        }
    }

    public SystemRepository save(SystemRepository systemRepository) {
        return repository.save(systemRepository);
    }
}
