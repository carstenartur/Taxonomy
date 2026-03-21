package com.taxonomy.dsl.storage;

import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the DSL Git storage layer.
 *
 * <p>Wires a {@link DslGitRepositoryFactory} backed by the existing HSQLDB
 * {@link SessionFactory} via the {@code sandbox-jgit-storage-hibernate}
 * pattern. All Git objects (blobs, trees, commits) are stored in the
 * {@code git_packs} database table; refs are stored as reftable data
 * within the same table.
 *
 * <p>No filesystem is used — the entire Git repository lives in the
 * database that Spring Boot already manages.
 *
 * <p>The {@link DslGitRepositoryFactory} is the single entry point for
 * obtaining per-workspace or system repository instances. All services
 * inject the factory and call {@code getSystemRepository()} to obtain
 * the shared system repository, or {@code getWorkspaceRepository(id)}
 * for per-workspace isolation.
 */
@Configuration
public class DslStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(DslStorageConfig.class);

    @Bean
    public DslGitRepositoryFactory dslGitRepositoryFactory(SessionFactory sessionFactory) {
        log.info("Creating DslGitRepositoryFactory (database-backed via SessionFactory)");
        return new DslGitRepositoryFactory(sessionFactory);
    }
}
