package com.taxonomy.workspace.service;

import com.taxonomy.architecture.service.CommitIndexSearchLifecycle;
import com.taxonomy.shared.config.SchemaContractMigration;
import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SystemRepositoryCatalogInitializerTest {

    private static final DefaultApplicationArguments NO_ARGS =
            new DefaultApplicationArguments(new String[0]);

    @Test
    void delegatesStartupToTransactionalServiceBoundary() {
        SystemRepositoryService service = mock(SystemRepositoryService.class);
        SystemRepositoryCatalogInitializer initializer =
                new SystemRepositoryCatalogInitializer(service);

        initializer.run(NO_ARGS);

        verify(service).ensureSystemRepository();
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
    void runnerOrderKeepsSchemaMigrationAheadAndSearchLifecycleBehind() {
        int schemaMigrationOrder = orderOf(SchemaContractMigration.class);
        int catalogInitializationOrder = orderOf(SystemRepositoryCatalogInitializer.class);
        int searchLifecycleOrder = orderOf(CommitIndexSearchLifecycle.class);

        assertThat(catalogInitializationOrder)
                .isGreaterThan(schemaMigrationOrder)
                .isLessThan(searchLifecycleOrder);
    }

    private static int orderOf(Class<?> type) {
        Order annotation = type.getAnnotation(Order.class);
        assertThat(annotation)
                .as(type.getName() + " must declare an explicit startup order")
                .isNotNull();
        return annotation.value();
    }
}
