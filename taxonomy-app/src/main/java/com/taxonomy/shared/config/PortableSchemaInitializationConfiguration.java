package com.taxonomy.shared.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.List;

/** The shipped Oracle/SQL Server profiles disable Flyway; prepare retained rows before JPA. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "false")
public class PortableSchemaInitializationConfiguration {
    @Bean(initMethod = "migrate")
    @ConditionalOnProperty(name = "taxonomy.schema-migration.enabled", havingValue = "true", matchIfMissing = true)
    Initialization portableSchemaInitialization(DataSource database) {
        return new Initialization(database);
    }

    public record Initialization(DataSource database) {
        public void migrate() { new SchemaContractMigration(database).migrate(); }
    }

    @Bean
    static BeanFactoryPostProcessor portableSchemaOrder() {
        return beans -> {
            if (beans.containsBeanDefinition("portableSchemaInitialization")
                    && beans.containsBeanDefinition("entityManagerFactory")) {
                var definition = beans.getBeanDefinition("entityManagerFactory");
                var prerequisites = new LinkedHashSet<String>();
                if (definition.getDependsOn() != null) prerequisites.addAll(List.of(definition.getDependsOn()));
                prerequisites.add("portableSchemaInitialization");
                definition.setDependsOn(prerequisites.toArray(String[]::new));
            }
        };
    }
}
