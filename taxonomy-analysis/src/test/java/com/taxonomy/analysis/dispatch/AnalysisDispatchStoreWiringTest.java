package com.taxonomy.analysis.dispatch;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The application runs with lazy initialization in most tests, but packaged
 * deployments instantiate eagerly. Component-registered stores must therefore
 * resolve their injection constructor without relying on a no-arg constructor.
 */
class AnalysisDispatchStoreWiringTest {

    @Test
    void componentRegisteredStoresUseTheirInjectionConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(EntityManager.class, () -> mock(EntityManager.class));
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.register(AnalysisDispatchStore.class, JpaAnalysisTaskCompletionStore.class);
            context.refresh();

            assertThat(context.getBean(AnalysisDispatchStore.class)).isNotNull();
            assertThat(context.getBean(JpaAnalysisTaskCompletionStore.class)).isNotNull();
        }
    }
}
