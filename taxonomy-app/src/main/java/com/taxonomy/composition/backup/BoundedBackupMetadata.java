package com.taxonomy.composition.backup;

import com.taxonomy.backup.*;
import javax.sql.DataSource;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.sql.*;
import java.util.*;

/** Existing metadata only: a limit+1 sentinel rejects an oversized inventory, never truncates it. */
final class BoundedBackupMetadata {
    private final DataSource database;
    BoundedBackupMetadata(DataSource database) { this.database = Objects.requireNonNull(database); }
    @FunctionalInterface interface Mapper<T> { T read(ResultSet row) throws SQLException, IOException; }

    <T> List<T> read(String sql, List<String> parameters, int limit, BackupCheckpoint checkpoint,
                     Mapper<T> mapper) throws IOException {
        if (limit < 0 || limit > BackupLimits.MAX_ITEMS) throw new IllegalArgumentException("Invalid metadata inventory limit");
        checkpoint.check();
        try (var connection = database.getConnection()) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (Rollback rollback = connection::rollback; var statement = connection.prepareStatement(sql)) {
                statement.setMaxRows(limit + 1); statement.setFetchSize(Math.min(200, limit + 1)); statement.setQueryTimeout(60);
                for (int i = 0; i < parameters.size(); i++) statement.setString(i + 1, parameters.get(i));
                checkpoint.check();
                try (var rows = statement.executeQuery()) {
                    List<T> values = new ArrayList<>();
                    while (rows.next()) {
                        checkpoint.check();
                        if (values.size() == limit) throw new IOException("Backup metadata inventory limit exceeded");
                        values.add(mapper.read(rows));
                    }
                    checkpoint.check(); return List.copyOf(values);
                }
            }
        } catch (InterruptedIOException cancelled) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Backup metadata inventory was cancelled");
        } catch (SQLException invalid) { throw new IOException("Backup metadata inventory is unavailable"); }
    }
    @FunctionalInterface private interface Rollback extends AutoCloseable { @Override void close() throws SQLException; }
}
