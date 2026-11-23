package com.taxonomy.versioning.service;

import com.taxonomy.versioning.repository.ArchitectureCommitIndexRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies retained commit-history documents and rebuilds only their search scope when needed.
 * Asynchronous initialization leaves HTTP startup free while history reads remain unavailable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 200)
@ConditionalOnProperty(
        name = "taxonomy.commit-index.search-rebuild-empty",
        havingValue = "true",
        matchIfMissing = true)
public class CommitIndexSearchLifecycle implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(
            CommitIndexSearchLifecycle.class);

    @Value("${taxonomy.analysis.runtime-role:all}")
    private String runtimeRole = "all";

    @Value("${taxonomy.init.async:false}")
    private boolean asyncInitialization;

    @Value("${spring.jpa.properties.hibernate.search.enabled:true}")
    private boolean searchEnabled = true;

    public enum State { WAITING, CHECKING, REBUILDING, READY, FAILED, DISABLED, STOPPED }

    private volatile State state = State.WAITING;
    private boolean stopped;
    private final AtomicBoolean started = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("taxonomy-history-index").factory());

    private final ArchitectureCommitIndexRepository indexRepository;
    private final CommitIndexSearchRebuilder searchRebuilder;

    public CommitIndexSearchLifecycle(
            ArchitectureCommitIndexRepository indexRepository,
            CommitIndexSearchRebuilder searchRebuilder) {
        this.indexRepository = indexRepository;
        this.searchRebuilder = searchRebuilder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!started.compareAndSet(false, true)) return;
        // Catalogue workers disable Hibernate Search and operate on admitted snapshots only.
        if (!searchEnabled || "worker".equalsIgnoreCase(runtimeRole.trim())) {
            transition(State.DISABLED);
            log.info("Global commit search initialization is disabled for this runtime");
            return;
        }
        if (asyncInitialization) {
            synchronized (this) {
                if (!stopped) executor.execute(() -> initialize(false));
            }
        } else {
            initialize(true);
        }
    }

    private void initialize(boolean failStartup) {
        if (!transition(State.CHECKING)) return;
        try {
            if (!searchRebuilder.hasCompleteCoverage(indexRepository.count())) {
                if (!transition(State.REBUILDING)) return;
                searchRebuilder.rebuildAll();
                if (!searchRebuilder.hasCompleteCoverage(indexRepository.count())) {
                    throw new IllegalStateException("Commit search index coverage is incomplete after rebuilding");
                }
                log.info("Rebuilt and verified the repository-scoped commit search index");
            } else {
                log.info("Reused the verified repository-scoped commit search index");
            }
            transition(State.READY);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            transition(State.FAILED);
            if (failStartup) throw new IllegalStateException("Commit search index rebuild was interrupted", exception);
            log.warn("Commit search index initialization was interrupted");
        } catch (RuntimeException exception) {
            transition(State.FAILED);
            if (failStartup) throw exception;
            log.error("Commit search index initialization failed (code=COMMIT_SEARCH_INIT_FAILED)");
        }
    }

    public State getState() { return state; }
    public boolean isReady() { return state == State.READY; }

    boolean isInitializing() {
        return switch (state) {
            case WAITING, CHECKING, REBUILDING -> true;
            default -> false;
        };
    }

    private synchronized boolean transition(State next) {
        if (stopped) return false;
        state = next;
        return true;
    }

    @PreDestroy
    public synchronized void stop() {
        stopped = true;
        state = State.STOPPED;
        executor.shutdownNow();
    }
}
