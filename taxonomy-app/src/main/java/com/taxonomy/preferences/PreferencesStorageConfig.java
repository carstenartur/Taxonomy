package com.taxonomy.preferences;

import com.taxonomy.preferences.storage.PreferencesGitRepository;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the preferences Git storage layer.
 *
 * <p>Wires a {@link PreferencesGitRepository} backed by the existing HSQLDB
 * {@link SessionFactory} via the {@code sandbox-jgit-storage-hibernate} pattern.
 * The preferences repository uses project name {@code "taxonomy-preferences"}, which
 * is <strong>separate</strong> from the Architecture DSL repository
 * ({@code "taxonomy-dsl"}). This ensures preferences history is completely
 * decoupled from DSL history.
 */
@Configuration
public class PreferencesStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(PreferencesStorageConfig.class);

    @Bean
    public PreferencesGitRepository preferencesGitRepository(SessionFactory sessionFactory) {
        log.info("Creating PreferencesGitRepository (HibernateRepository → database-backed)");
        return new PreferencesGitRepository(sessionFactory);
    }
}
