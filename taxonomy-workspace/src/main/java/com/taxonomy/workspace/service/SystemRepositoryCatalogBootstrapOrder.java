package com.taxonomy.workspace.service;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Declares the startup dependency without coupling the catalogue module to the
 * workspace implementation type.
 */
@Component
public class SystemRepositoryCatalogBootstrapOrder
        implements BeanFactoryPostProcessor, PriorityOrdered {

    static final String TAXONOMY_SERVICE_BEAN = "taxonomyService";
    static final String CATALOG_INITIALIZER_BEAN =
            "systemRepositoryCatalogInitializer";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        BeanDefinition taxonomyService =
                beanFactory.getBeanDefinition(TAXONOMY_SERVICE_BEAN);
        String[] currentDependencies = taxonomyService.getDependsOn();
        if (currentDependencies == null) {
            taxonomyService.setDependsOn(CATALOG_INITIALIZER_BEAN);
        } else if (Arrays.stream(currentDependencies)
                .noneMatch(CATALOG_INITIALIZER_BEAN::equals)) {
            String[] dependencies = Arrays.copyOf(
                    currentDependencies, currentDependencies.length + 1);
            dependencies[currentDependencies.length] = CATALOG_INITIALIZER_BEAN;
            taxonomyService.setDependsOn(dependencies);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
