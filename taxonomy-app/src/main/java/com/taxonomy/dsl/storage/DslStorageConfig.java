package com.taxonomy.dsl.storage;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration for database-backed DSL and preferences Git storage. */
@Configuration
public class DslStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(DslStorageConfig.class);

    /**
     * Expose the reusable storage library through its public factory contract.
     *
     * <p>The application owns the JPA persistence context and its native Hibernate
     * {@link SessionFactory}. Repository handles opened by this factory own only
     * their JGit repository resources and must never close the shared session
     * factory.</p>
     */
    @Bean
    public HibernateRepositoryFactory hibernateRepositoryFactory(
            EntityManagerFactory entityManagerFactory) {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        log.info("Creating HibernateRepositoryFactory for application-managed SessionFactory");
        return new DefaultHibernateRepositoryFactory(sessionFactory);
    }

    /** Create the Taxonomy-specific logical-repository router. */
    @Bean
    public DslGitRepositoryFactory dslGitRepositoryFactory(
            HibernateRepositoryFactory hibernateRepositoryFactory) {
        log.info("Creating DslGitRepositoryFactory backed by jgit-storage-hibernate-core");
        return new DslGitRepositoryFactory(hibernateRepositoryFactory);
    }
}
