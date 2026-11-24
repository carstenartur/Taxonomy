package com.taxonomy.composition.analysis;

import com.taxonomy.preferences.storage.GitPromptOverrideStore;
import com.taxonomy.shared.features.ConditionalOnFeature;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Wires the database-backed override store when the optional analysis feature is installed. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnFeature("analysis")
public class PromptTemplateStorageConfiguration {

    @Bean(destroyMethod = "close")
    GitPromptOverrideStore promptTemplateOverrideStore(HibernateRepositoryFactory factory, ObjectMapper json) {
        return new GitPromptOverrideStore(factory, json);
    }
}
