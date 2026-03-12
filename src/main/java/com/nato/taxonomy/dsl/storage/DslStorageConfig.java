package com.nato.taxonomy.dsl.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the DSL Git storage layer.
 *
 * <p>Wires a {@link DslGitRepository} as a singleton Spring bean.
 * Currently uses an in-memory DFS repository; when
 * {@code sandbox-jgit-storage-hibernate} is available as a Maven
 * artifact, this configuration can be updated to wire a
 * {@code HibernateRepository} backed by the existing HSQLDB
 * SessionFactory.
 *
 * <pre>
 * // Future: wire HibernateRepository from sandbox-jgit-storage-hibernate
 * &#64;Bean
 * DslGitRepository dslGitRepository(SessionFactory sessionFactory) {
 *     var provider = new HibernateSessionFactoryProvider(sessionFactory);
 *     var hibernateRepo = new TaxonomyHibernateRepository(provider, "taxonomy-dsl");
 *     return new DslGitRepository(hibernateRepo);
 * }
 * </pre>
 */
@Configuration
public class DslStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(DslStorageConfig.class);

    @Bean
    public DslGitRepository dslGitRepository() {
        log.info("Creating DslGitRepository (in-memory DFS backend)");
        return new DslGitRepository();
    }
}
