package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.identity.StableIdentityHash;
import javax.sql.DataSource;
import java.io.*;
import java.nio.file.*;
import java.sql.*;

/** Connection-witnessed identity. Public evidence contains no URL, username or credentials. */
public record TargetDatabaseIdentity(SourceDatabaseFamily family, String destinationFingerprint,
                                     String catalog, String schema) {
    private static final String MEMORY_PREFIX = "jdbc:hsqldb:mem:";
    private static final String FILE_PREFIX = "jdbc:hsqldb:file:";
    public TargetDatabaseIdentity {
        if (family == null || destinationFingerprint == null || !destinationFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid target database identity");
        catalog = scope(catalog); schema = scope(schema);
    }

    public static TargetDatabaseIdentity inspect(DataSource database, BackupCheckpoint checkpoint) throws IOException {
        try {
            checkpoint.check();
            try (var connection = database.getConnection()) {
                var identity = inspectConnection(connection); checkpoint.check(); return identity;
            }
        } catch (InterruptedIOException cancelled) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Restore target inspection was cancelled");
        } catch (IOException unavailable) {
            throw new IOException("Restore target identity is unavailable or unproven");
        } catch (SQLException | IllegalArgumentException unavailable) {
            throw new IOException("Restore target identity is unavailable or unproven");
        }
    }

    public static TargetDatabaseIdentity proveIsolated(DataSource active, DataSource candidate,
                                                       BackupCheckpoint checkpoint) throws IOException {
        var source = inspect(active, checkpoint); var target = inspect(candidate, checkpoint);
        // Initial local-HSQL support requires a different physical database. A changed default schema
        // alone cannot prove that a JPA default_schema or qualified owner SQL avoids the active schema.
        if (source.destinationFingerprint().equals(target.destinationFingerprint()))
            throw new IOException("Restore target is not isolated from the active database");
        return target;
    }

    static TargetDatabaseIdentity inspectConnection(Connection connection) throws SQLException, IOException {
        var metadata = connection.getMetaData();
        var family = SourceDatabaseFamily.fromProductName(metadata.getDatabaseProductName());
        // Only actual tested local HSQL destinations have a canonical identity proof here.
        // Remote endpoints and other vendors require their own witnessed physical-identity adapters.
        if (family != SourceDatabaseFamily.HSQLDB) throw new IOException("Restore target isolation is unproven for this database family");
        String url = metadata.getURL();
        if (url == null || url.length() > 4096 || url.chars().anyMatch(Character::isISOControl))
            throw new IOException("Restore target identity is unavailable or unproven");
        int parameters = url.indexOf(';'); String destination = parameters < 0 ? url : url.substring(0, parameters);
        String prefix;
        if (destination.regionMatches(true, 0, MEMORY_PREFIX, 0, MEMORY_PREFIX.length())) {
            prefix = MEMORY_PREFIX; String name = destination.substring(prefix.length());
            if (name.isBlank()) throw new IOException("Restore target identity is unavailable or unproven");
            destination = prefix + name.toLowerCase(java.util.Locale.ROOT);
        } else if (destination.regionMatches(true, 0, FILE_PREFIX, 0, FILE_PREFIX.length())) {
            prefix = FILE_PREFIX; var path = Path.of(destination.substring(prefix.length())).toAbsolutePath().normalize();
            if (path.getFileName() == null || path.getParent() == null)
                throw new IOException("Restore target identity is unavailable or unproven");
            destination = prefix + path.getParent().toRealPath().resolve(path.getFileName());
        } else throw new IOException("Restore target isolation is unproven for this database endpoint");
        return new TargetDatabaseIdentity(family, StableIdentityHash.sha256(destination), connection.getCatalog(), connection.getSchema());
    }

    private static String scope(String value) {
        if (value == null) return "";
        if (value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid target scope");
        return value;
    }
}
