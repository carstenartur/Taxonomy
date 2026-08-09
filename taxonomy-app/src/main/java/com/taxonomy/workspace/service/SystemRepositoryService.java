package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryOwnerType;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Manages the catalog of central architecture repositories. */
@Service
public class SystemRepositoryService {

    /** Historic storage name that must remain stable for existing installations. */
    public static final String PRIMARY_STORAGE_NAME = "taxonomy-dsl";

    private static final Logger log = LoggerFactory.getLogger(SystemRepositoryService.class);
    private static final Pattern SLUG_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final int MAX_PROVISIONING_ERROR_LENGTH = 2000;

    private final SystemRepositoryRepository repository;

    public SystemRepositoryService(SystemRepositoryRepository repository) {
        this.repository = repository;
    }

    /**
     * Ensure a primary catalog entry exists, backfill multi-repository metadata,
     * and erase credentials persisted by releases that stored external Git tokens
     * in plaintext.
     *
     * <p>Initialization fails closed. Continuing after a failed cleanup could
     * leave a usable plaintext credential in the database while the operator
     * assumes migration succeeded.</p>
     */
    @PostConstruct
    @Transactional
    public void ensureSystemRepository() {
        try {
            var primaryRepository = repository.findByPrimaryRepoTrue();
            if (primaryRepository.isEmpty()) {
                SystemRepository systemRepository = new SystemRepository();
                systemRepository.setRepositoryId(UUID.randomUUID().toString());
                systemRepository.setStorageRepositoryName(PRIMARY_STORAGE_NAME);
                systemRepository.setSlug("shared-architecture");
                systemRepository.setDisplayName("Shared Architecture Repository");
                systemRepository.setDescription("Default central architecture repository");
                systemRepository.setVisibility(RepositoryVisibility.ORGANIZATION);
                systemRepository.setLifecycleState(RepositoryLifecycleState.ACTIVE);
                systemRepository.setProvisioningError(null);
                systemRepository.setOwnerType(RepositoryOwnerType.SYSTEM);
                systemRepository.setOwnerId("system");
                systemRepository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
                systemRepository.setDefaultBranch("draft");
                systemRepository.setPrimaryRepo(true);
                systemRepository.setCreatedBy("system");
                systemRepository.setCreatedAt(Instant.now());
                systemRepository.setUpdatedAt(Instant.now());
                repository.save(systemRepository);
                log.info("Created primary architecture repository catalog entry "
                        + "(storage={}, topology=INTERNAL_SHARED, branch=draft)",
                        PRIMARY_STORAGE_NAME);
                return;
            }

            SystemRepository existing = primaryRepository.get();
            boolean changed = backfillPrimaryCatalogMetadata(existing);
            if (existing.hasLegacyPlaintextCredential()) {
                existing.clearLegacyPlaintextCredential();
                changed = true;
                log.warn("Removed a legacy plaintext external Git credential from the database. "
                        + "Configure TAXONOMY_EXTERNAL_GIT_TOKEN through deployment secret storage.");
            }
            if (changed) {
                existing.setUpdatedAt(Instant.now());
                repository.save(existing);
            }
        } catch (RuntimeException exception) {
            log.error("Could not initialize the architecture repository catalog safely", exception);
            throw new IllegalStateException(
                    "Architecture repository initialization or credential cleanup failed",
                    exception);
        }
    }

    /** Compatibility adapter for callers not yet migrated to explicit repository IDs. */
    public SystemRepository getPrimaryRepository() {
        return repository.findByPrimaryRepoTrue()
                .orElseThrow(() -> new IllegalStateException(
                        "No primary architecture repository configured"));
    }

    public SystemRepository getRepository(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId must not be blank");
        }
        return repository.findByRepositoryId(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Architecture repository not found: " + repositoryId));
    }

    public List<SystemRepository> listActiveRepositories() {
        return repository.findByLifecycleStateOrderByDisplayNameAsc(
                RepositoryLifecycleState.ACTIVE);
    }

    /**
     * Reserve a new central repository identity in the catalog.
     *
     * <p>The entry remains {@link RepositoryLifecycleState#PROVISIONING} until the
     * caller has created the logical JGit repository and its initial branch. A
     * failed allocation therefore remains diagnosable and can be retried or cleaned
     * explicitly instead of appearing as an active but unusable repository.</p>
     */
    @Transactional
    public SystemRepository createCentralRepository(
            String displayName,
            String requestedSlug,
            String description,
            RepositoryVisibility visibility,
            String ownerId,
            String defaultBranch) {
        String name = requireText(displayName, "displayName");
        String slug = normalizeSlug(requestedSlug != null && !requestedSlug.isBlank()
                ? requestedSlug : name);
        if (repository.findBySlug(slug).isPresent()) {
            throw new IllegalArgumentException("Repository slug already exists: " + slug);
        }

        String repositoryId = UUID.randomUUID().toString();
        String storageName = "central-" + repositoryId;
        if (repository.findByStorageRepositoryName(storageName).isPresent()) {
            throw new IllegalStateException("Generated repository storage name already exists");
        }

        String owner = requireText(ownerId, "ownerId");
        Instant now = Instant.now();
        SystemRepository architectureRepository = new SystemRepository();
        architectureRepository.setRepositoryId(repositoryId);
        architectureRepository.setStorageRepositoryName(storageName);
        architectureRepository.setSlug(slug);
        architectureRepository.setDisplayName(name);
        architectureRepository.setDescription(description);
        architectureRepository.setVisibility(
                visibility != null ? visibility : RepositoryVisibility.PRIVATE);
        architectureRepository.setLifecycleState(RepositoryLifecycleState.PROVISIONING);
        architectureRepository.setProvisioningError(null);
        architectureRepository.setOwnerType(RepositoryOwnerType.USER);
        architectureRepository.setOwnerId(owner);
        architectureRepository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        architectureRepository.setDefaultBranch(
                defaultBranch == null || defaultBranch.isBlank() ? "draft" : defaultBranch.strip());
        architectureRepository.setPrimaryRepo(false);
        architectureRepository.setCreatedBy(owner);
        architectureRepository.setCreatedAt(now);
        architectureRepository.setUpdatedAt(now);
        return repository.save(architectureRepository);
    }

    @Transactional
    public SystemRepository createForkMetadata(
            String sourceRepositoryId,
            String sourceBranch,
            String forkPointCommit,
            String displayName,
            String slug,
            String description,
            RepositoryVisibility visibility,
            String ownerId) {
        SystemRepository source = getRepository(sourceRepositoryId);
        String branch = sourceBranch == null || sourceBranch.isBlank()
                ? source.getDefaultBranch()
                : sourceBranch.strip();
        SystemRepository fork = createCentralRepository(
                displayName,
                slug,
                description,
                visibility,
                ownerId,
                branch);
        fork.setUpstreamRepositoryId(source.getRepositoryId());
        fork.setUpstreamBranch(branch);
        fork.setForkPointCommit(forkPointCommit);
        fork.setUpdatedAt(Instant.now());
        return repository.save(fork);
    }

    @Transactional
    public SystemRepository markProvisioningReady(String repositoryId) {
        SystemRepository architectureRepository = getRepository(repositoryId);
        architectureRepository.setLifecycleState(RepositoryLifecycleState.ACTIVE);
        architectureRepository.setProvisioningError(null);
        architectureRepository.setUpdatedAt(Instant.now());
        return repository.save(architectureRepository);
    }

    @Transactional
    public SystemRepository markProvisioningFailed(String repositoryId, String reason) {
        SystemRepository architectureRepository = getRepository(repositoryId);
        architectureRepository.setLifecycleState(RepositoryLifecycleState.FAILED);
        architectureRepository.setProvisioningError(normalizeProvisioningError(reason));
        architectureRepository.setUpdatedAt(Instant.now());
        return repository.save(architectureRepository);
    }

    /** Return the configured primary branch, falling back during early startup. */
    public String getSharedBranch() {
        try {
            return getPrimaryRepository().getDefaultBranch();
        } catch (Exception exception) {
            log.debug("Primary repository not available, falling back to 'draft': {}",
                    exception.getMessage());
            return "draft";
        }
    }

    public String getDefaultBranch(String repositoryId) {
        return getRepository(repositoryId).getDefaultBranch();
    }

    @Transactional
    public SystemRepository save(SystemRepository systemRepository) {
        systemRepository.setUpdatedAt(Instant.now());
        return repository.save(systemRepository);
    }

    private boolean backfillPrimaryCatalogMetadata(SystemRepository existing) {
        boolean changed = false;
        if (existing.getStorageRepositoryName() == null
                || existing.getStorageRepositoryName().isBlank()) {
            existing.setStorageRepositoryName(PRIMARY_STORAGE_NAME);
            changed = true;
        }
        if (existing.getSlug() == null || existing.getSlug().isBlank()) {
            existing.setSlug("shared-architecture");
            changed = true;
        }
        if (existing.getVisibility() == null) {
            existing.setVisibility(RepositoryVisibility.ORGANIZATION);
            changed = true;
        }
        if (existing.getLifecycleState() == null) {
            existing.setLifecycleState(RepositoryLifecycleState.ACTIVE);
            changed = true;
        }
        if (existing.getOwnerType() == null) {
            existing.setOwnerType(RepositoryOwnerType.SYSTEM);
            changed = true;
        }
        if (existing.getOwnerId() == null || existing.getOwnerId().isBlank()) {
            existing.setOwnerId("system");
            changed = true;
        }
        if (existing.getCreatedBy() == null || existing.getCreatedBy().isBlank()) {
            existing.setCreatedBy("system");
            changed = true;
        }
        if (existing.getUpdatedAt() == null) {
            existing.setUpdatedAt(existing.getCreatedAt() != null
                    ? existing.getCreatedAt() : Instant.now());
            changed = true;
        }
        return changed;
    }

    private static String normalizeSlug(String value) {
        String normalized = requireText(value, "slug")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (!SLUG_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Repository slug is invalid: " + value);
        }
        return normalized;
    }

    private static String normalizeProvisioningError(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Repository provisioning failed";
        }
        String normalized = reason.strip();
        return normalized.length() <= MAX_PROVISIONING_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_PROVISIONING_ERROR_LENGTH);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
