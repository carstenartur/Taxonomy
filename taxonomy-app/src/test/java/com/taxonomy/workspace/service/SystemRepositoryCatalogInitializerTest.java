package com.taxonomy.workspace.service;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SystemRepositoryCatalogInitializerTest {

    @Test
    void delegatesStartupToTransactionalServiceBoundary() {
        SystemRepositoryService service = mock(SystemRepositoryService.class);
        SystemRepositoryCatalogInitializer initializer =
                new SystemRepositoryCatalogInitializer(service);

        initializer.initialize();

        verify(service).ensureSystemRepository();
    }

    @Test
    void initializerOwnsLifecycleCallback() throws Exception {
        Method method = SystemRepositoryCatalogInitializer.class
                .getDeclaredMethod("initialize");

        assertThat(method.isAnnotationPresent(PostConstruct.class)).isTrue();
    }

    @Test
    void serviceOwnsTransactionButNoLifecycleCallback() throws Exception {
        Method method = SystemRepositoryService.class
                .getDeclaredMethod("ensureSystemRepository");

        assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(method.isAnnotationPresent(PostConstruct.class)).isFalse();
        assertThat(Arrays.stream(SystemRepositoryService.class.getDeclaredMethods())
                .noneMatch(candidate -> candidate.isAnnotationPresent(PostConstruct.class)))
                .isTrue();
    }

    @Test
    void taxonomyBootstrapDependsOnCatalogInitialization() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        RootBeanDefinition taxonomyService = new RootBeanDefinition(Object.class);
        taxonomyService.setDependsOn("existingDependency");
        beanFactory.registerBeanDefinition(
                SystemRepositoryCatalogBootstrapOrder.TAXONOMY_SERVICE_BEAN,
                taxonomyService);

        new SystemRepositoryCatalogBootstrapOrder()
                .postProcessBeanFactory(beanFactory);

        assertThat(taxonomyService.getDependsOn()).containsExactly(
                "existingDependency",
                SystemRepositoryCatalogBootstrapOrder.CATALOG_INITIALIZER_BEAN);
    }
}
