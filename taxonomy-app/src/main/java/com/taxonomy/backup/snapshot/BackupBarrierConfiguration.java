package com.taxonomy.backup.snapshot;

import com.taxonomy.backup.BackupWriteBarrier;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.time.Duration;

/** One guarded application datasource; ORM, JDBC and the shared JGit SessionFactory use it. */
@Configuration(proxyBeanMethods = false)
public class BackupBarrierConfiguration {
    @Bean
    @ConditionalOnProperty(name = "taxonomy.backup.enabled", havingValue = "false", matchIfMissing = true)
    BackupWriteBarrier disabledBackupWriteBarrier() { return BackupWriteBarrier.disabled(); }

    @Bean
    @ConditionalOnProperty(name = "taxonomy.backup.enabled", havingValue = "true")
    BackupMaintenanceLease backupMaintenanceLease(DataSource database) {
        if (!(database instanceof GuardedBackupDataSource guarded))
            throw new IllegalStateException("Backup requires the guarded application datasource");
        return guarded.coordinator();
    }

    @Bean
    @ConditionalOnProperty(name = "taxonomy.backup.enabled", havingValue = "true")
    org.springframework.boot.jdbc.metadata.DataSourcePoolMetadataProvider backupPoolMetadataProvider() {
        return database -> database instanceof GuardedBackupDataSource guarded ? guarded.poolMetadata() : null;
    }

    @Bean
    @ConditionalOnProperty(name = "taxonomy.backup.enabled", havingValue = "true")
    static DataSourceFence backupDataSourceFence(Environment environment) {
        return new DataSourceFence(Duration.ofSeconds(environment.getProperty("taxonomy.backup.writer-lease-seconds", Long.class, 300L)),
                Duration.ofSeconds(environment.getProperty("taxonomy.backup.startup-wait-seconds", Long.class, 30L)));
    }

    static final class DataSourceFence implements BeanPostProcessor, ApplicationListener<ApplicationReadyEvent>, DisposableBean, Ordered {
        private final Duration lifetime;
        private final Duration startupWait;
        private HikariDataSource coordination;
        private GuardedBackupDataSource guarded;
        DataSourceFence(Duration lifetime, Duration startupWait) { this.lifetime = lifetime; this.startupWait = startupWait; }

        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof DataSource)) return bean;
            if (!"dataSource".equals(name) || !(bean instanceof HikariDataSource application) || guarded != null)
                throw new IllegalStateException("Backup requires one application-managed Hikari datasource; unguarded sources are unsupported");
            coordination = BackupCoordinationPool.open(application);
            BackupMaintenanceLease.initialize(coordination);
            var leases = new BackupMaintenanceLease(coordination, lifetime);
            var startup = leases.beginStartup(startupWait);
            guarded = new GuardedBackupDataSource(application, leases, startup);
            return guarded;
        }

        @Override public void onApplicationEvent(ApplicationReadyEvent event) {
            if (guarded == null) throw new IllegalStateException("Backup writer boundary was not installed");
            guarded.finishStartup();
        }
        @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 100; }
        @Override public void destroy() { if (coordination != null) coordination.close(); }
    }
}
