package com.taxonomy.versioning.service;

import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.shared.service.AppInitializationStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Initialises the DSL Git repository with a {@code draft} branch on application startup.
 *
 * <p>When the application starts for the first time (empty database), the Git repository
 * has no branches and no commits. This causes the frontend to display
 * "Git status unavailable" and the version history to show errors, because
 * {@code /api/git/state?branch=draft} returns null values.
 *
 * <p>This component creates an initial commit on the {@code draft} branch containing
 * the DSL export of the current taxonomy, so that Git-based features (status bar,
 * version history, variants, compare) work correctly from the start.
 *
 * <p>In synchronous init mode (default), the taxonomy is already loaded by the time
 * {@link ApplicationReadyEvent} fires. In asynchronous init mode, this component
 * checks {@link AppInitializationStateService#isReady()} and skips if not ready —
 * the first user-initiated DSL commit will create the branch in that case.
 */
@Component
public class GitRepositoryBootstrap {

    private static final Logger log = LoggerFactory.getLogger(GitRepositoryBootstrap.class);

    private final DslGitRepository gitRepository;
    private final TaxDslExportService exportService;
    private final AppInitializationStateService stateService;

    public GitRepositoryBootstrap(DslGitRepository gitRepository,
                                  TaxDslExportService exportService,
                                  AppInitializationStateService stateService) {
        this.gitRepository = gitRepository;
        this.exportService = exportService;
        this.stateService = stateService;
    }

    /**
     * Creates an initial commit on the {@code draft} branch if it does not already exist.
     *
     * <p>This method is idempotent: on subsequent restarts with persistent storage,
     * the branch already exists and the method returns without making changes.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeDraftBranch() {
        if (!stateService.isReady()) {
            log.debug("Taxonomy not yet loaded (async mode) — skipping draft branch bootstrap.");
            return;
        }

        try {
            if (gitRepository.getHeadCommit("draft") != null) {
                log.debug("Draft branch already exists — skipping bootstrap.");
                return;
            }

            String dsl = exportService.exportAll("default");
            String commitId = gitRepository.commitDsl("draft", dsl, "system",
                    "Initial taxonomy materialization");

            log.info("Bootstrapped 'draft' branch with initial taxonomy DSL " +
                    "(commit={}, {} chars)", commitId.substring(0, 7), dsl.length());
        } catch (IOException e) {
            log.warn("Failed to bootstrap 'draft' branch: {}", e.getMessage());
        }
    }
}
