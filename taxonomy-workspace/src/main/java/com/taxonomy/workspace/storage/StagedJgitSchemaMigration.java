package com.taxonomy.workspace.storage;

import com.taxonomy.backup.BackupCheckpoint;
import org.flywaydb.core.Flyway;
import javax.sql.DataSource;
import java.io.*;
import java.util.Objects;

/** Versioned storage schema only. The host must prove the supplied staged database is isolated.
 * This adapter never selects, opens or creates an application repository and never adopts legacy storage. */
public final class StagedJgitSchemaMigration {
    private StagedJgitSchemaMigration() { }
    /** Registers the library-owned model for validation against the separately migrated target. */
    public static void configureModel(org.hibernate.cfg.Configuration configuration) {
        Objects.requireNonNull(configuration);
        io.github.carstenartur.jgit.storage.hibernate.config.CoreEntities.annotatedClasses().forEach(configuration::addAnnotatedClass);
    }
    public static void migrate(DataSource explicitStagedDatabase, BackupCheckpoint checkpoint) throws IOException {
        Objects.requireNonNull(explicitStagedDatabase); Objects.requireNonNull(checkpoint);
        try {
            checkpoint.check();
            var configuration = Flyway.configure().dataSource(explicitStagedDatabase);
            new JgitStorageSchemaMigrationConfig().jgitStorageFlywayConfigurationCustomizer().customize(configuration);
            JgitStorageSchemaMigrationConfig.migrateCoreSchema(configuration.load(), false);
            checkpoint.check();
        } catch (InterruptedIOException cancelled) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Staged Git schema migration was cancelled");
        } catch (RuntimeException failure) { throw new IOException("Staged Git schema migration failed"); }
    }
}
