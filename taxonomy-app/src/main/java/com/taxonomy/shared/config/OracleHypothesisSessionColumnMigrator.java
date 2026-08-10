package com.taxonomy.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Converts Oracle's historic national-character analysis-session identifier to
 * the database character set before the portable hypothesis-scope migration.
 *
 * <p>{@code analysis_session_id} is a technical identifier (UUID/provider key),
 * not localized content. Oracle refuses a {@code COALESCE} between NVARCHAR2 and
 * the VARCHAR2 sentinel used by the cross-database scope contract. The migration
 * copies values through {@code TO_CHAR}, which yields VARCHAR2, then resumes the
 * normal portable migration. Its temporary-column states are explicitly
 * resumable because Oracle DDL commits implicitly.</p>
 */
final class OracleHypothesisSessionColumnMigrator {

    private static final Logger log = LoggerFactory.getLogger(
            OracleHypothesisSessionColumnMigrator.class);
    private static final String TABLE = "relation_hypothesis";
    private static final String SOURCE_COLUMN = "analysis_session_id";
    private static final String TEMP_COLUMN = "analysis_session_id_dbcs";
    private static final String SESSION_INDEX = "idx_hyp_session";

    private final DataSource dataSource;

    OracleHypothesisSessionColumnMigrator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            if (!isOracle(connection)) {
                return;
            }
            TableRef table = findTable(connection, TABLE);
            if (table == null) {
                return;
            }

            ColumnRef source = findColumn(connection, table, SOURCE_COLUMN);
            ColumnRef temporary = findColumn(connection, table, TEMP_COLUMN);

            if (source == null) {
                if (temporary != null) {
                    String targetName = canonicalIdentifier(
                            connection.getMetaData(), SOURCE_COLUMN);
                    renameColumn(connection, table, temporary.name(), targetName);
                    ensureSessionIndex(connection, table, targetName);
                    log.info("Completed interrupted Oracle hypothesis session-column rename");
                }
                return;
            }
            if (!isNationalCharacterType(source.typeName())) {
                if (temporary != null) {
                    execute(connection, "ALTER TABLE " + qualified(connection, table)
                            + " DROP COLUMN " + quoted(connection, temporary.name()));
                }
                ensureSessionIndex(connection, table, source.name());
                return;
            }

            String temporaryName;
            if (temporary == null) {
                temporaryName = canonicalIdentifier(
                        connection.getMetaData(), TEMP_COLUMN);
                execute(connection, "ALTER TABLE " + qualified(connection, table)
                        + " ADD " + quoted(connection, temporaryName)
                        + " VARCHAR2(" + Math.max(255, source.size()) + ")");
            } else {
                temporaryName = temporary.name();
            }

            execute(connection, "UPDATE " + qualified(connection, table)
                    + " SET " + quoted(connection, temporaryName)
                    + " = TO_CHAR(" + quoted(connection, source.name()) + ")"
                    + " WHERE " + quoted(connection, source.name()) + " IS NOT NULL");

            dropIndexesOrConstraintsReferencingSource(connection, table);
            execute(connection, "ALTER TABLE " + qualified(connection, table)
                    + " DROP COLUMN " + quoted(connection, source.name()));
            String targetName = canonicalIdentifier(
                    connection.getMetaData(), SOURCE_COLUMN);
            renameColumn(connection, table, temporaryName, targetName);
            ensureSessionIndex(connection, table, targetName);
            log.info("Converted Oracle relation_hypothesis.analysis_session_id from {} to VARCHAR2",
                    source.typeName());
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "Unable to normalize Oracle hypothesis analysis-session column safely",
                    error);
        }
    }

    private void dropIndexesOrConstraintsReferencingSource(
            Connection connection,
            TableRef table) throws SQLException {
        for (IndexDefinition index : indexes(connection, table)) {
            if (!index.columns().contains(SOURCE_COLUMN)) {
                continue;
            }
            SQLException constraintFailure;
            try {
                execute(connection, "ALTER TABLE " + qualified(connection, table)
                        + " DROP CONSTRAINT " + quoted(connection, index.name()));
                continue;
            } catch (SQLException error) {
                constraintFailure = error;
            }
            try {
                execute(connection, "DROP INDEX "
                        + qualifiedIndex(connection, table, index.name()));
            } catch (SQLException indexFailure) {
                indexFailure.addSuppressed(constraintFailure);
                throw indexFailure;
            }
        }
    }

    private void ensureSessionIndex(
            Connection connection,
            TableRef table,
            String sourceColumnName) throws SQLException {
        boolean columnAlreadyIndexed = indexes(connection, table).stream()
                .anyMatch(index -> index.columns().equals(List.of(SOURCE_COLUMN)));
        if (columnAlreadyIndexed) {
            return;
        }
        String indexName = canonicalIdentifier(
                connection.getMetaData(), SESSION_INDEX);
        execute(connection, "CREATE INDEX " + quoted(connection, indexName)
                + " ON " + qualified(connection, table)
                + " (" + quoted(connection, sourceColumnName) + ")");
    }

    private List<IndexDefinition> indexes(
            Connection connection,
            TableRef table) throws SQLException {
        Map<String, Map<Short, String>> byName = new LinkedHashMap<>();
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                table.catalog(), table.schema(), table.name(), false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                String column = indexes.getString("COLUMN_NAME");
                short type = indexes.getShort("TYPE");
                if (name == null || column == null
                        || type == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                short ordinal = indexes.getShort("ORDINAL_POSITION");
                byName.computeIfAbsent(name, ignored -> new TreeMap<>())
                        .put(ordinal, column.toLowerCase(Locale.ROOT));
            }
        }
        List<IndexDefinition> result = new ArrayList<>();
        byName.forEach((name, columns) -> result.add(
                new IndexDefinition(name, List.copyOf(columns.values()))));
        result.sort(Comparator.comparing(IndexDefinition::name));
        return result;
    }

    private void renameColumn(
            Connection connection,
            TableRef table,
            String from,
            String to) throws SQLException {
        execute(connection, "ALTER TABLE " + qualified(connection, table)
                + " RENAME COLUMN " + quoted(connection, from)
                + " TO " + quoted(connection, to));
    }

    private ColumnRef findColumn(
            Connection connection,
            TableRef table,
            String logicalColumn) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(
                table.catalog(), table.schema(), table.name(), "%")) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                if (name != null && name.equalsIgnoreCase(logicalColumn)) {
                    return new ColumnRef(
                            name,
                            columns.getString("TYPE_NAME"),
                            columns.getInt("COLUMN_SIZE"));
                }
            }
        }
        return null;
    }

    private TableRef findTable(
            Connection connection,
            String logicalName) throws SQLException {
        String preferredSchema = safeSchema(connection);
        List<TableRef> matches = new ArrayList<>();
        try (ResultSet tables = connection.getMetaData().getTables(
                connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (name != null && name.equalsIgnoreCase(logicalName)) {
                    matches.add(new TableRef(
                            tables.getString("TABLE_CAT"),
                            tables.getString("TABLE_SCHEM"),
                            name));
                }
            }
        }
        return matches.stream()
                .sorted(Comparator.comparing(table -> preferredSchema != null
                        && preferredSchema.equalsIgnoreCase(table.schema()) ? 0 : 1))
                .findFirst()
                .orElse(null);
    }

    private static boolean isOracle(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT)
                .contains("oracle");
    }

    static boolean isNationalCharacterType(String typeName) {
        if (typeName == null) {
            return false;
        }
        String normalized = typeName.toUpperCase(Locale.ROOT);
        return normalized.contains("NVARCHAR") || normalized.equals("NCHAR");
    }

    static String canonicalIdentifier(
            DatabaseMetaData metadata,
            String identifier) throws SQLException {
        if (metadata.storesUpperCaseIdentifiers()) {
            return identifier.toUpperCase(Locale.ROOT);
        }
        if (metadata.storesLowerCaseIdentifiers()) {
            return identifier.toLowerCase(Locale.ROOT);
        }
        return identifier;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        log.debug("Oracle hypothesis session-column migration SQL: {}", sql);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String qualified(Connection connection, TableRef table) throws SQLException {
        String tableName = quoted(connection, table.name());
        return table.schema() == null || table.schema().isBlank()
                ? tableName
                : quoted(connection, table.schema()) + "." + tableName;
    }

    private String qualifiedIndex(
            Connection connection,
            TableRef table,
            String indexName) throws SQLException {
        String quotedIndex = quoted(connection, indexName);
        return table.schema() == null || table.schema().isBlank()
                ? quotedIndex
                : quoted(connection, table.schema()) + "." + quotedIndex;
    }

    private String quoted(Connection connection, String identifier) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            return identifier;
        }
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError ignored) {
            return null;
        }
    }

    private record TableRef(String catalog, String schema, String name) {
    }

    private record ColumnRef(String name, String typeName, int size) {
    }

    private record IndexDefinition(String name, List<String> columns) {
    }
}
