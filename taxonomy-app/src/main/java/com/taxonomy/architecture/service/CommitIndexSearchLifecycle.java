package com.taxonomy.architecture.service;

import com.taxonomy.architecture.repository.ArchitectureCommitIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Keeps the persistent Lucene commit index aligned after the tenant migration.
 *
 * <p>V8 intentionally removes legacy relational rows whose repository provenance
 * cannot be reconstructed. If the projection table is empty, rebuilding this one
 * Hibernate Search scope purges any corresponding legacy documents that a JDBC
 * migration could not remove through ORM events.</p>
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
        if (indexRepository.count() > 0) {
            return;
        }
        try {
            searchRebuilder.rebuildAll();
            log.info("Purged/rebuilt the empty repository-scoped commit search index");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Commit search index rebuild was interrupted", exception);
        }
    }
}
