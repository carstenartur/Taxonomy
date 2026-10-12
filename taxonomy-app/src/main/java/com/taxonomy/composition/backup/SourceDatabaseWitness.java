package com.taxonomy.composition.backup;

import com.taxonomy.backup.*;
import javax.sql.DataSource;
import java.io.*;
import java.sql.SQLException;

/** Caller holds the capture fence; only the actual vendor family is retained. */
public final class SourceDatabaseWitness {
    private SourceDatabaseWitness() { }

    public static SourceDatabaseMetadata capture(DataSource database, BackupCheckpoint checkpoint) throws IOException {
        try {
            checkpoint.check();
            try (var connection = database.getConnection()) {
                var family = SourceDatabaseFamily.fromProductName(connection.getMetaData().getDatabaseProductName());
                checkpoint.check(); return new SourceDatabaseMetadata(1, family);
            }
        } catch (InterruptedIOException cancelled) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Source database witness was cancelled");
        } catch (SQLException | IllegalArgumentException unavailable) {
            throw new IOException("Source database family is unavailable or unsupported");
        }
    }
}
