package com.nato.taxonomy.config;

import com.nato.taxonomy.service.TaxonomyService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom health indicator that reports taxonomy initialization status.
 * Visible at {@code /actuator/health} as the "taxonomy" component.
 */
@Component
public class TaxonomyHealthIndicator implements HealthIndicator {

    private final TaxonomyService taxonomyService;

    public TaxonomyHealthIndicator(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    @Override
    public Health health() {
        String status = taxonomyService.getInitStatus();
        boolean initialized = taxonomyService.isInitialized();

        Health.Builder builder = initialized ? Health.up() : Health.down();
        builder.withDetail("initStatus", status);
        builder.withDetail("initialized", initialized);

        // Add memory info
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory();
        builder.withDetail("heapUsedMB", heapUsed / (1024 * 1024));
        builder.withDetail("heapMaxMB", heapMax / (1024 * 1024));
        builder.withDetail("heapUsagePercent", Math.round((double) heapUsed / heapMax * 100));

        return builder.build();
    }
}
