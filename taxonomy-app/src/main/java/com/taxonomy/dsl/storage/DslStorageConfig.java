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
 */
@Configuration
public class DslStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(DslStorageConfig.class);

    @Bean
    public DslGitRepository dslGitRepository(SessionFactory sessionFactory) {
        log.info("Creating DslGitRepository (HibernateRepository → database-backed via SessionFactory)");
        return new DslGitRepository(sessionFactory);
    }
}

