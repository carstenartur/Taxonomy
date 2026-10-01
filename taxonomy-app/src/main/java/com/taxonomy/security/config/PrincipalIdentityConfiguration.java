package com.taxonomy.security.config;

import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.security.service.PrincipalIdentityService;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.List;

/** Upgrade the identity contract before JPA validates existing application tables. */
@Configuration(proxyBeanMethods = false)
public class PrincipalIdentityConfiguration {
    @Bean(initMethod = "migrate")
    PrincipalSchemaInitialization principalSchemaInitialization(DataSource database) {
        return new PrincipalSchemaInitialization(database);
    }

    @Bean PrincipalIdentityService principalIdentityService(DataSource database, PrincipalSchemaInitialization initialized) {
        return new PrincipalIdentityService(database);
    }

    @Bean static BeanFactoryPostProcessor principalSchemaOrder() {
        return beans -> {
            if (beans.containsBeanDefinition("flywayInitializer"))
                dependsOn(beans, "principalSchemaInitialization", "flywayInitializer");
            if (beans.containsBeanDefinition("entityManagerFactory"))
                dependsOn(beans, "entityManagerFactory", "principalSchemaInitialization");
        };
    }

    private static void dependsOn(org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans,
                                   String bean, String prerequisite) {
        var definition = beans.getBeanDefinition(bean);
        var dependencies = new LinkedHashSet<String>();
        if (definition.getDependsOn() != null) dependencies.addAll(List.of(definition.getDependsOn()));
        dependencies.add(prerequisite);
        definition.setDependsOn(dependencies.toArray(String[]::new));
    }

    public record PrincipalSchemaInitialization(DataSource database) {
        public void migrate() { PrincipalSchemaMigration.migrate(database); }
    }
}
