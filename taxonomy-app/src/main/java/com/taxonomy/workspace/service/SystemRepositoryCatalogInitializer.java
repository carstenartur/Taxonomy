package com.taxonomy.workspace.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Initializes the central repository catalog after the Spring context is ready.
 *
 * <p>The initializer deliberately delegates to a separate Spring-managed service
 * bean. That inter-bean call crosses the transactional proxy, unlike invoking a
 * {@code @Transactional} method from the service bean's own {@code @PostConstruct}
 * callback.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SystemRepositoryCatalogInitializer implements ApplicationRunner {

    private final SystemRepositoryService systemRepositoryService;

    public SystemRepositoryCatalogInitializer(
            SystemRepositoryService systemRepositoryService) {
        this.systemRepositoryService = systemRepositoryService;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        systemRepositoryService.ensureSystemRepository();
    }
}
