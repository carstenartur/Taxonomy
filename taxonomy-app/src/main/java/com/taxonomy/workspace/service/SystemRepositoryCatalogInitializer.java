package com.taxonomy.workspace.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Initializes the central repository catalog through the transactional service proxy.
 *
 * <p>The runner executes after the highest-precedence portable schema migration and
 * before repository-sensitive search lifecycle runners. Delegating to a separate
 * Spring-managed service bean makes the {@code @Transactional} boundary effective;
 * invoking the same method from that service bean's own {@code @PostConstruct}
 * callback would bypass the proxy.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
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
