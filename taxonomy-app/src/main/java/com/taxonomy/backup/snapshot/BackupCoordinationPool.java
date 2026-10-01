package com.taxonomy.backup.snapshot;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Properties;
import java.util.UUID;

/** Lease operations must remain available when writers occupy every application connection. */
public final class BackupCoordinationPool {
    private BackupCoordinationPool() { }

    public static HikariDataSource open(HikariDataSource application) {
        var settings = new HikariConfig();
        application.copyStateTo(settings);
        var properties = new Properties();
        properties.putAll(application.getDataSourceProperties());
        settings.setDataSourceProperties(properties);
        settings.setPoolName("taxonomy-backup-coordination-" + UUID.randomUUID());
        settings.setMaximumPoolSize(2);
        settings.setMinimumIdle(0);
        settings.setConnectionTimeout(5_000);
        settings.setAutoCommit(true);
        settings.setReadOnly(false);
        settings.setRegisterMbeans(false);
        settings.setMetricRegistry(null);
        settings.setMetricsTrackerFactory(null);
        settings.setScheduledExecutor(null);
        return new HikariDataSource(settings);
    }
}
