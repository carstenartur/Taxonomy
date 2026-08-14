package com.taxonomy.workspace.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Initializes the central repository catalog through the transactional service proxy.
 *
 * <p>This lifecycle callback belongs to a separate Spring bean. Calling the public
 * service method therefore crosses the {@link SystemRepositoryService} proxy and makes
 * its {@code @Transactional} boundary effective. The taxonomy bootstrap explicitly
 * depends on this initializer so built-in seed relations can always bind to the primary
 * repository.</p>
 */
@Component
public class SystemRepositoryCatalogInitializer {

    private final SystemRepositoryService systemRepositoryService;

    public SystemRepositoryCatalogInitializer(
            SystemRepositoryService systemRepositoryService) {
        this.systemRepositoryService = systemRepositoryService;
    }

    @PostConstruct
    void initialize() {
        systemRepositoryService.ensureSystemRepository();
    }
}
