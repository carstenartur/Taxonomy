package com.taxonomy.backup.jobs;

import com.taxonomy.backup.runtime.BackupAuthorizationService;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.snapshot.BackupMaintenanceLease;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/** Runs after normal module composition, and only when the complete source inventory is available. */
@AutoConfiguration
@ConditionalOnProperty(name = "taxonomy.backup.enabled", havingValue = "true")
@ConditionalOnBean({BackupMaintenanceLease.class, BackupCaptureSource.class})
public class BackupJobsConfiguration {
    @Bean BackupJobLimits backupJobLimits(Environment environment) {
        return new BackupJobLimits(integer(environment, "heavy-concurrency", 1), integer(environment, "current-concurrency", 2),
                integer(environment, "queue-capacity", 64), Duration.ofSeconds(number(environment, "lease-seconds", 30)),
                Duration.ofHours(number(environment, "retention-hours", 24)), number(environment, "max-retained-bytes", 64L << 30),
                number(environment, "max-temporary-bytes", 72L << 30));
    }
    @Bean JdbcBackupJobStore backupJobStore(BackupMaintenanceLease barrier, BackupJobLimits limits) { return barrier.jobs(limits); }
    @Bean BackupJobStorage backupJobStorage(Environment environment) throws IOException { return new BackupJobStorage(root(environment)); }
    @Bean @ConditionalOnMissingBean(ArchiveProtectionProvider.class)
    ArchiveProtectionProvider backupArchiveProtection(Environment environment) {
        String key = environment.getProperty("taxonomy.backup.keyset-file", "");
        return key.isBlank() ? ArchiveProtectionProvider.unprotected() : DeploymentArchiveProtection.load(Path.of(key), root(environment));
    }
    @Bean BackupArchiveWriter backupArchiveWriter(ArchiveProtectionProvider protection,
            com.taxonomy.backup.runtime.BackupFeaturePrerequisites prerequisites) {
        return new BackupArchiveWriter(protection, ArchiveLimits.defaults(), prerequisites);
    }
    @Bean BackupJobService backupJobService(JdbcBackupJobStore store, BackupAuthorizationService authorization,
                                          BackupJobStorage storage, ArchiveProtectionProvider protection) {
        return new BackupJobService(store, authorization, storage, protection.encrypted());
    }
    @Bean BackupJobWorker backupJobWorker(JdbcBackupJobStore store, BackupAuthorizationService authorization,
                                        BackupJobStorage storage, BackupCaptureSource source, BackupArchiveWriter writer) {
        return new BackupJobWorker(store, authorization, storage, source, writer);
    }
    @Bean BackupJobRunner backupJobRunner(BackupJobWorker worker, BackupJobLimits limits) { return new BackupJobRunner(worker, limits); }
    private static Path root(Environment environment) { return Path.of(environment.getProperty("taxonomy.backup.directory", "./data/backups")); }
    private static int integer(Environment e, String name, int fallback) { return e.getProperty("taxonomy.backup.jobs." + name, Integer.class, fallback); }
    private static long number(Environment e, String name, long fallback) { return e.getProperty("taxonomy.backup.jobs." + name, Long.class, fallback); }
}
