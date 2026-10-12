package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.identity.StableIdentityHash;
import javax.sql.DataSource;
import java.io.*;
import java.sql.*;
import java.util.*;

/** Read-only target evidence: structural changes and any existing row invalidate an empty-target plan. */
public record TargetSchemaEvidence(String schemaSha256, Set<String> tableNames, boolean populated) {
    public TargetSchemaEvidence {
        if (schemaSha256 == null || !schemaSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid target schema proof");
        tableNames = Set.copyOf(tableNames);
        if (tableNames.size() > BackupLimits.MAX_ITEMS) throw new IllegalArgumentException("Too many target tables");
        tableNames.forEach(TargetSchemaEvidence::identifier);
    }

    public static TargetSchemaEvidence inspect(DataSource database, BackupCheckpoint checkpoint) throws IOException {
        try {
            checkpoint.check();
            try (var connection = database.getConnection()) {
                if (SourceDatabaseFamily.fromProductName(connection.getMetaData().getDatabaseProductName()) != SourceDatabaseFamily.HSQLDB)
                    throw new IOException("Target schema proof is unsupported for this database family");
                connection.setReadOnly(true); connection.setAutoCommit(false);
                try (Rollback rollback = new Rollback(connection)) {
                    var metadata = connection.getMetaData(); String catalog = connection.getCatalog(), schema = connection.getSchema();
                    var tables = new TreeSet<String>();
                    try (var rows = metadata.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
                        while (rows.next()) {
                            checkpoint.check();
                            if (!"TABLE".equals(rows.getString("TABLE_TYPE"))) throw new IOException("Restore target contains unsupported schema objects");
                            tables.add(identifier(rows.getString("TABLE_NAME")));
                            if (tables.size() > BackupLimits.MAX_ITEMS) throw new IOException("Target schema inventory limit exceeded");
                        }
                    }
                    try (var statement = connection.prepareStatement("select trigger_name from information_schema.triggers where trigger_schema=?")) {
                        statement.setString(1, schema); statement.setMaxRows(1); statement.setQueryTimeout(60); checkpoint.check();
                        try (var rows = statement.executeQuery()) {
                            if (rows.next()) throw new IOException("Restore target contains unsupported schema objects");
                            checkpoint.check();
                        }
                    }
                    var canonical = new StringBuilder(); boolean populated = false; int columns = 0, indexes = 0;
                    for (String table : tables) {
                        checkpoint.check(); append(canonical, "table", table);
                        var definitions = new TreeMap<Integer, String>();
                        try (var rows = metadata.getColumns(catalog, schema, table, "%")) {
                            while (rows.next()) {
                                checkpoint.check();
                                if (!table.equals(rows.getString("TABLE_NAME"))) continue;
                                if (++columns > BackupLimits.MAX_ITEMS) throw new IOException("Target schema inventory limit exceeded");
                                int ordinal = rows.getInt("ORDINAL_POSITION");
                                String definition = identifier(rows.getString("COLUMN_NAME")) + ":" + rows.getInt("DATA_TYPE")
                                        + ":" + rows.getInt("COLUMN_SIZE") + ":" + rows.getInt("DECIMAL_DIGITS") + ":" + rows.getInt("NULLABLE")
                                        + ":" + Objects.toString(rows.getString("COLUMN_DEF"), "<null>")
                                        + ":" + rows.getString("IS_AUTOINCREMENT") + ":" + rows.getString("IS_GENERATEDCOLUMN");
                                if (definition.length() > 2048) throw new IOException("Target schema evidence limit exceeded");
                                if (definitions.put(ordinal, definition) != null) throw new IOException("Ambiguous target schema inventory");
                            }
                        }
                        for (var column : definitions.entrySet()) append(canonical, "column:" + column.getKey(), column.getValue());
                        var indexDefinitions = new TreeSet<String>();
                        try (var rows = metadata.getIndexInfo(catalog, schema, table, false, false)) {
                            while (rows.next()) {
                                checkpoint.check(); String name = rows.getString("INDEX_NAME");
                                if (name == null) continue;
                                if (++indexes > BackupLimits.MAX_ITEMS) throw new IOException("Target schema inventory limit exceeded");
                                indexDefinitions.add(identifier(name) + ":" + rows.getBoolean("NON_UNIQUE") + ":"
                                        + rows.getInt("ORDINAL_POSITION") + ":" + identifier(rows.getString("COLUMN_NAME")));
                            }
                        }
                        for (String index : indexDefinitions) append(canonical, "index", index);
                        var foreignKeys = new TreeSet<String>();
                        try (var rows = metadata.getImportedKeys(catalog, schema, table)) {
                            while (rows.next()) {
                                checkpoint.check();
                                if (++indexes > BackupLimits.MAX_ITEMS) throw new IOException("Target schema inventory limit exceeded");
                                foreignKeys.add(Objects.toString(rows.getString("FK_NAME"), "<unnamed>") + ":" + rows.getInt("KEY_SEQ")
                                        + ":" + identifier(rows.getString("FKCOLUMN_NAME")) + ":" + identifier(rows.getString("PKTABLE_SCHEM"))
                                        + ":" + identifier(rows.getString("PKTABLE_NAME")) + ":" + identifier(rows.getString("PKCOLUMN_NAME"))
                                        + ":" + rows.getInt("UPDATE_RULE") + ":" + rows.getInt("DELETE_RULE") + ":" + rows.getInt("DEFERRABILITY"));
                            }
                        }
                        for (String foreignKey : foreignKeys) append(canonical, "foreign-key", foreignKey);
                        try (var statement = connection.prepareStatement("select c.constraint_name,c.check_clause from information_schema.check_constraints c"
                                + " join information_schema.table_constraints t on t.constraint_catalog=c.constraint_catalog"
                                + " and t.constraint_schema=c.constraint_schema and t.constraint_name=c.constraint_name"
                                + " where t.table_schema=? and t.table_name=? order by c.constraint_name")) {
                            statement.setString(1, schema); statement.setString(2, table);
                            statement.setMaxRows(BackupLimits.MAX_ITEMS + 1); statement.setQueryTimeout(60); checkpoint.check();
                            try (var rows = statement.executeQuery()) {
                                while (rows.next()) {
                                    checkpoint.check();
                                    if (++indexes > BackupLimits.MAX_ITEMS) throw new IOException("Target schema inventory limit exceeded");
                                    String clause = rows.getString(2);
                                    if (clause == null || clause.length() > 8192) throw new IOException("Target schema evidence limit exceeded");
                                    append(canonical, "check:" + identifier(rows.getString(1)), clause);
                                }
                            }
                        }
                        boolean hasRows;
                        try (var statement = connection.prepareStatement("select 1 from "
                                + (schema == null ? "" : quote(schema) + ".") + quote(table))) {
                            statement.setMaxRows(1); statement.setQueryTimeout(60); checkpoint.check();
                            try (var rows = statement.executeQuery()) { hasRows = rows.next(); checkpoint.check(); }
                        }
                        populated |= hasRows; append(canonical, "populated", Boolean.toString(hasRows));
                    }
                    checkpoint.check(); return new TargetSchemaEvidence(StableIdentityHash.sha256(canonical.toString()), tables, populated);
                }
            }
        } catch (InterruptedIOException cancelled) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Restore target schema inspection was cancelled");
        } catch (SQLException | IllegalArgumentException invalid) { throw new IOException("Restore target schema inspection failed"); }
    }

    private static void append(StringBuilder result, String key, String value) throws IOException {
        result.append(key.length()).append(':').append(key).append(value.length()).append(':').append(value).append('\n');
        if (result.length() > BackupLimits.MAX_MANIFEST_BYTES) throw new IOException("Target schema evidence limit exceeded");
    }
    private static String identifier(String value) {
        if (value == null || value.isBlank() || value.length() > BackupLimits.MAX_TEXT_LENGTH || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid target schema identifier");
        return value;
    }
    private static String quote(String value) { return '"' + identifier(value).replace("\"", "\"\"") + '"'; }
    private record Rollback(Connection connection) implements AutoCloseable {
        @Override public void close() throws SQLException { connection.rollback(); }
    }
}
