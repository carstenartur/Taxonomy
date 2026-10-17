package com.taxonomy.shared.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Same retained-data contract on a fast JDBC database and on actual Oracle. */
abstract class OracleHypothesisSessionMigrationContract {

    enum Interruption {
        NONE(null), ADD(" ADD "), COPY("UPDATE "), CONSTRAINT(" DROP CONSTRAINT "),
        INDEX("DROP INDEX "), SOURCE(" DROP COLUMN \"ANALYSIS_SESSION_ID\""),
        RENAME(" RENAME COLUMN "), REINDEX("CREATE INDEX ");

        final String statement;

        Interruption(String statement) {
            this.statement = statement;
        }
    }

    abstract DataSource database() throws Exception;

    int longestSessionLength() {
        return 255;
    }

    @ParameterizedTest(name = "retains historical data after {0}")
    @EnumSource(Interruption.class)
    void resumesCommittedHistoricalMigration(Interruption interruption) throws Exception {
        DataSource database = database();
        seed(database);
        if (interruption != Interruption.NONE) {
            AtomicBoolean interrupted = new AtomicBoolean();
            DataSource failing = intercept(database, sql -> {
                if (sql.contains(interruption.statement) && interrupted.compareAndSet(false, true)) {
                    throw new SQLException("simulated connection loss after committed " + interruption);
                }
            });
            assertThatThrownBy(() -> new OracleHypothesisSessionColumnMigrator(failing).migrate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("simulated connection loss after committed " + interruption);
            assertThat(interrupted).isTrue();
        }
        new OracleHypothesisSessionColumnMigrator(database).migrate();
        assertRetained(database);
        new OracleHypothesisSessionColumnMigrator(database).migrate();
        assertRetained(database);
    }

    @Test
    void refreshesPartiallyCopiedValuesIncludingSessionThatBecameNull() throws Exception {
        DataSource database = database();
        seed(database);
        try (Connection connection = database.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("ALTER TABLE relation_hypothesis ADD analysis_session_id_dbcs VARCHAR2(255)");
            // An earlier interrupted copy may predate a session update. The source
            // remains authoritative until it has been dropped, including SQL NULL.
            sql.execute("UPDATE relation_hypothesis SET analysis_session_id_dbcs = 'stale-session'");
        }
        new OracleHypothesisSessionColumnMigrator(database).migrate();
        assertRetained(database);
    }

    @Test
    void removesRedundantTemporaryColumnWhenSourceIsAlreadyNormalized() throws Exception {
        DataSource database = database();
        seed(database);
        new OracleHypothesisSessionColumnMigrator(database).migrate();
        try (Connection connection = database.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("ALTER TABLE relation_hypothesis ADD analysis_session_id_dbcs VARCHAR2(255)");
        }
        new OracleHypothesisSessionColumnMigrator(database).migrate();
        assertRetained(database);
    }

    private void seed(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement sql = connection.createStatement()) {
            // Every scenario uses a fresh historical schema; Oracle commits these DDL statements.
            try {
                sql.execute("DROP TABLE relation_hypothesis");
            } catch (SQLException ignored) {
                // First scenario: table does not exist yet.
            }
            sql.execute("CREATE TABLE relation_hypothesis (id INTEGER PRIMARY KEY, "
                    + "source_node_id VARCHAR2(255), target_node_id VARCHAR2(255), "
                    + "relation_type VARCHAR2(255), status VARCHAR2(255), analysis_session_id NVARCHAR2(255), "
                    + "CONSTRAINT uq_hyp_legacy UNIQUE (source_node_id, target_node_id, relation_type, analysis_session_id))");
            sql.execute("CREATE INDEX idx_hyp_session ON relation_hypothesis (analysis_session_id)");
            sql.execute("CREATE INDEX idx_hyp_status ON relation_hypothesis (status)");
            try (var insert = connection.prepareStatement("INSERT INTO relation_hypothesis "
                    + "(id, source_node_id, target_node_id, relation_type, status, analysis_session_id) "
                    + "VALUES (?, 'source', 'target', 'USES', 'PROPOSED', ?)")) {
                for (int id = 1; id <= 3; id++) {
                    insert.setInt(1, id);
                    insert.setString(2, switch (id) {
                        case 1 -> "provider/session-123";
                        case 2 -> "x".repeat(longestSessionLength());
                        default -> null;
                    });
                    insert.executeUpdate();
                }
            }
        }
    }

    private void assertRetained(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement sql = connection.createStatement()) {
            try (ResultSet rows = sql.executeQuery("SELECT analysis_session_id FROM relation_hypothesis ORDER BY id")) {
                List<String> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                assertThat(values).containsExactly("provider/session-123", "x".repeat(longestSessionLength()), null);
            }
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(connection.getCatalog(), connection.getSchema(),
                    "RELATION_HYPOTHESIS", "%")) {
                List<String> names = new ArrayList<>();
                while (columns.next()) {
                    names.add(columns.getString("COLUMN_NAME"));
                    if ("ANALYSIS_SESSION_ID".equals(columns.getString("COLUMN_NAME"))) {
                        assertThat(columns.getString("TYPE_NAME")).doesNotContain("NVARCHAR", "NCHAR");
                    }
                }
                assertThat(names).contains("ANALYSIS_SESSION_ID").doesNotContain("ANALYSIS_SESSION_ID_DBCS");
            }
            try (ResultSet indexes = metadata.getIndexInfo(connection.getCatalog(), connection.getSchema(),
                    "RELATION_HYPOTHESIS", false, false)) {
                List<String> indexed = new ArrayList<>();
                while (indexes.next()) {
                    indexed.add(indexes.getString("INDEX_NAME") + ":" + indexes.getString("COLUMN_NAME"));
                }
                assertThat(indexed).contains("IDX_HYP_SESSION:ANALYSIS_SESSION_ID", "IDX_HYP_STATUS:STATUS");
                assertThat(indexed.stream().filter("IDX_HYP_SESSION:ANALYSIS_SESSION_ID"::equals)).hasSize(1);
            }
        }
    }

    @FunctionalInterface
    interface AfterStatement {
        void executed(String sql) throws SQLException;
    }

    /** Forwards real SQL; faults happen only after the delegate has committed its work. */
    static DataSource intercept(DataSource delegate, AfterStatement after) {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = delegate.getConnection();
                return proxy(Connection.class, (method, args) -> {
                    Object result = invoke(connection, method, args);
                    if (method.getName().equals("createStatement")) {
                        Statement statement = (Statement) result;
                        return proxy(Statement.class, (statementMethod, statementArgs) -> {
                            Object executed = invoke(statement, statementMethod, statementArgs);
                            if (statementMethod.getName().equals("execute")) {
                                after.executed((String) statementArgs[0]);
                            }
                            return executed;
                        });
                    }
                    return result;
                });
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return getConnection();
            }
        };
    }

    @FunctionalInterface
    interface Invocation {
        Object call(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, args) -> invocation.call(method, args)));
    }

    static Object invoke(Object delegate, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
