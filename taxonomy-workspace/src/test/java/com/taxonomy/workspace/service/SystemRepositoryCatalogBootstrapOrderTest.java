package com.taxonomy.workspace.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SystemRepositoryCatalogBootstrapOrderTest {

    private final SystemRepositoryCatalogBootstrapOrder bootstrapOrder =
            new SystemRepositoryCatalogBootstrapOrder();

    @Test
    void partialContextWithoutTaxonomyServiceIsIgnored() {
        var beanFactory = new DefaultListableBeanFactory();

        assertThatCode(() -> bootstrapOrder.postProcessBeanFactory(beanFactory))
                .doesNotThrowAnyException();
    }

    @Test
    void taxonomyServiceStillDependsOnCatalogInitializer() {
        var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(
                SystemRepositoryCatalogBootstrapOrder.TAXONOMY_SERVICE_BEAN,
                new GenericBeanDefinition());

        bootstrapOrder.postProcessBeanFactory(beanFactory);

        assertThat(beanFactory.getBeanDefinition(
                SystemRepositoryCatalogBootstrapOrder.TAXONOMY_SERVICE_BEAN)
                .getDependsOn())
                .containsExactly(
                        SystemRepositoryCatalogBootstrapOrder.CATALOG_INITIALIZER_BEAN);
    }

    @Test
    void existingStartupDependenciesArePreserved() {
        var beanFactory = new DefaultListableBeanFactory();
        var taxonomyService = new GenericBeanDefinition();
        taxonomyService.setDependsOn("otherBootstrap");
        beanFactory.registerBeanDefinition(
                SystemRepositoryCatalogBootstrapOrder.TAXONOMY_SERVICE_BEAN,
                taxonomyService);

        bootstrapOrder.postProcessBeanFactory(beanFactory);

        assertThat(beanFactory.getBeanDefinition(
                SystemRepositoryCatalogBootstrapOrder.TAXONOMY_SERVICE_BEAN)
                .getDependsOn())
                .containsExactly(
                        "otherBootstrap",
                        SystemRepositoryCatalogBootstrapOrder.CATALOG_INITIALIZER_BEAN);
    }
}
