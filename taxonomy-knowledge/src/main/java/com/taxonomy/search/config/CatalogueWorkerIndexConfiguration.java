package com.taxonomy.search.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Worker data comes from task snapshots, so even opening the global Lucene projection is unnecessary. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taxonomy.analysis.runtime-role", havingValue = "worker")
public class CatalogueWorkerIndexConfiguration {
    @Bean
    HibernatePropertiesCustomizer frozenCatalogueWorkerHibernateProperties() {
        return properties -> properties.put("hibernate.search.enabled", false);
    }
}
