package com.taxonomy.preferences;

import com.taxonomy.preferences.storage.GitPromptOverrideStore;
import com.taxonomy.preferences.storage.PreferencesGitRepository;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Spring configuration for the isolated preferences and prompt override repositories. */
@Configuration
public class PreferencesStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(PreferencesStorageConfig.class);

    @Bean
    public PreferencesGitRepository preferencesGitRepository(
            HibernateRepositoryFactory hibernateRepositoryFactory) {
        log.info("Creating database-backed PreferencesGitRepository");
        return new PreferencesGitRepository(hibernateRepositoryFactory);
    }

    @Bean(destroyMethod = "close")
    public GitPromptOverrideStore promptTemplateOverrideStore(HibernateRepositoryFactory factory, ObjectMapper json) {
        return new GitPromptOverrideStore(factory, json);
    }
}
