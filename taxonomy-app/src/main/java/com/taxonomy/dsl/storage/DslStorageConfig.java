package com.taxonomy.dsl.storage;

import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the DSL Git storage layer.
 *
 * <p>Wires a {@link DslGitRepository} backed by the existing HSQLDB
 * {@link SessionFactory} via the {@code sandbox-jgit-storage-hibernate}
 * pattern. All Git objects (blobs, trees, commits) are stored in the
 * {@code git_packs} database table; refs are stored as reftable data
 * within the same table.
 *
 * <p>No filesystem is used — the entire Git repository lives in the
 * database that Spring Boot already manages.
 *
 * <p>The {@link DslGitRepositoryFactory} is the primary entry point for
 * obtaining per-workspace or system repository instances. A singleton
 * {@link DslGitRepository} bean is retained for backward compatibility
 * with existing services that inject it directly.
 */
@Configuration
public class DslStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(DslStorageConfig.class);

    @Bean
    public DslGitRepositoryFactory dslGitRepositoryFactory(SessionFactory sessionFactory) {
        log.info("Creating DslGitRepositoryFactory (database-backed via SessionFactory)");
        return new DslGitRepositoryFactory(sessionFactory);
    }

    /**
     * Legacy bean for backward compatibility — delegates to the factory's
     * system repository. Existing services that inject {@link DslGitRepository}
     * directly will continue to receive the shared system repository.
     */
    @Bean
    public DslGitRepository dslGitRepository(DslGitRepositoryFactory factory) {
        log.info("Creating DslGitRepository (system repository via factory)");
        return factory.getSystemRepository();
    }
}

