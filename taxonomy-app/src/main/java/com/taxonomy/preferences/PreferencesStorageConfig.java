package com.taxonomy.preferences;

import com.taxonomy.preferences.storage.PreferencesGitRepository;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration for the independently versioned preferences repository. */
@Configuration
public class PreferencesStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(PreferencesStorageConfig.class);

    @Bean
    public PreferencesGitRepository preferencesGitRepository(
            HibernateRepositoryFactory hibernateRepositoryFactory) {
        log.info("Creating database-backed PreferencesGitRepository");
        return new PreferencesGitRepository(hibernateRepositoryFactory);
    }
}
