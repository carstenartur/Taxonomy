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
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Portable JDBC contract for proposal review Git-authority metadata. */
final class ProposalReviewAuthoritySchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(
            ProposalReviewAuthoritySchemaMigrator.class);
    private static final String TABLE = "relation_proposal";
    private static final String REVIEW_INDEX = "idx_proposal_review_commit";

    private final DataSource dataSource;

    ProposalReviewAuthoritySchemaMigrator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                TableRef table = findTable(connection, TABLE);
                if (table == null) {
                    connection.commit();
                    return;
                }
                ensureColumn(connection, table, "review_branch", "VARCHAR(255)");
                ensureColumn(connection, table, "review_commit_id", "VARCHAR(40)");
                ensureColumn(connection, table, "review_causation_id", "VARCHAR(255)");
                ensureReviewIndex(connection, table);
                connection.commit();
                log.debug("Verified portable proposal review Git-authority columns");
            } catch (SQLException | RuntimeException error) {
                rollbackQuietly(connection, error);
                throw new IllegalStateException(
                        "Unable to migrate proposal review Git authority safely",
                        error);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "Unable to open database for proposal review migration",
                    error);
        }
    }

    private void ensureColumn(
            Connection connection,
            TableRef table,
            String column,
            String definition) throws SQLException {
        if (columnExists(connection, table, column)) {
            return;
        }
        execute(connection, "ALTER TABLE " + qualified(connection, table)
                + " ADD " + quoted(connection, column) + " " + definition);
    }

    private void ensureReviewIndex(
            Connection connection,
            TableRef table) throws SQLException {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                table.catalog(), table.schema(), table.name(), false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name != null && name.equalsIgnoreCase(REVIEW_INDEX)) {
                    return;
                }
            }
        }
        execute(connection, "CREATE INDEX " + quoted(connection, REVIEW_INDEX)
                + " ON " + qualified(connection, table)
                + " (" + quoted(connection, "repository_id") + ", "
                + quoted(connection, "review_commit_id") + ")");
    }

    private boolean columnExists(
            Connection connection,
            TableRef table,
            String logicalColumn) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(
                table.catalog(), table.schema(), table.name(), "%")) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                if (name != null && name.equalsIgnoreCase(logicalColumn)) {
                    return true;
                }
            }
        }
        return false;
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

    private void execute(Connection connection, String sql) throws SQLException {
        log.debug("Proposal review schema migration SQL: {}", sql);
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

    private String quoted(Connection connection, String identifier) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String canonical = identifier;
        if (metadata.storesUpperCaseIdentifiers()) {
            canonical = identifier.toUpperCase(Locale.ROOT);
        } else if (metadata.storesLowerCaseIdentifiers()) {
            canonical = identifier.toLowerCase(Locale.ROOT);
        }
        String quote = metadata.getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            return canonical;
        }
        return quote + canonical.replace(quote, quote + quote) + quote;
    }

    private String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError ignored) {
            return null;
        }
    }

    private void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record TableRef(String catalog, String schema, String name) {
    }
}
