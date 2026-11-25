package com.taxonomy.preferences.storage;

import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/** Imported by the analysis host configuration after its feature check. */
public class PromptOverrideStorageBeans {
    @Bean(destroyMethod = "close")
    GitPromptOverrideStore promptTemplateOverrideStore(HibernateRepositoryFactory factory, ObjectMapper json) {
        return new GitPromptOverrideStore(factory, json);
    }
}
