package com.taxonomy.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OracleHypothesisSessionColumnMigratorTest extends OracleHypothesisSessionMigrationContract {

    @Override
    int longestSessionLength() {
        // HSQL's TO_CHAR implementation has a 100-character result limit.
        // The real Oracle contract additionally exercises the 255-character boundary.
        return 64;
    }

    @Override
    DataSource database() {
        // HSQL executes migration SQL against real rows in Oracle syntax mode.
        // Adapt its metadata aliases and its one unsupported Oracle RENAME spelling;
        // the companion Oracle IT remains the authority for Oracle semantics.
        var delegate = new DriverManagerDataSource(
                "jdbc:hsqldb:mem:oracle_upgrade_" + UUID.randomUUID()
                        + ";sql.syntax_ora=true", "SA", "");
        var national = new AtomicBoolean(true);
        DataSource tracked = intercept(delegate, sql -> {
            if (sql.contains(" DROP COLUMN \"ANALYSIS_SESSION_ID\"")) {
                national.set(false);
            }
        });
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                var connection = tracked.getConnection();
                return proxy(Connection.class, (method, args) -> {
                    Object result = invoke(connection, method, args);
                    if (method.getName().equals("createStatement")) {
                        var statement = (Statement) result;
                        return proxy(Statement.class, (statementMethod, statementArgs) -> {
                            if (statementMethod.getName().equals("execute")
                                    && ((String) statementArgs[0]).contains(" RENAME COLUMN ")) {
                                statementArgs[0] = ((String) statementArgs[0])
                                        .replace(" RENAME COLUMN ", " ALTER COLUMN ")
                                        .replace(" TO ", " RENAME TO ");
                            }
                            return invoke(statement, statementMethod, statementArgs);
                        });
                    }
                    if (!method.getName().equals("getMetaData")) {
                        return result;
                    }
                    var metadata = (DatabaseMetaData) result;
                    return proxy(DatabaseMetaData.class, (metadataMethod, metadataArgs) -> {
                        if (metadataMethod.getName().equals("getDatabaseProductName")) {
                            return "Oracle metadata over HSQL JDBC";
                        }
                        Object value = invoke(metadata, metadataMethod, metadataArgs);
                        if (metadataMethod.getName().equals("getColumns")) {
                            var columns = (ResultSet) value;
                            return proxy(ResultSet.class, (columnMethod, columnArgs) -> {
                                if (columnMethod.getName().equals("getString")
                                        && "TYPE_NAME".equals(columnArgs[0]) && national.get()
                                        && "ANALYSIS_SESSION_ID".equals(columns.getString("COLUMN_NAME"))) {
                                    return "NVARCHAR2";
                                }
                                return invoke(columns, columnMethod, columnArgs);
                            });
                        }
                        return value;
                    });
                });
            }

            @Override
            public Connection getConnection(String username, String password)
                    throws SQLException {
                return getConnection();
            }
        };
    }

    @Test
    void canonicalizesUnquotedLogicalNamesToOracleMetadataCase() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(metadata.storesUpperCaseIdentifiers()).thenReturn(true);

        assertThat(OracleHypothesisSessionColumnMigrator.canonicalIdentifier(
                metadata, "analysis_session_id"))
                .isEqualTo("ANALYSIS_SESSION_ID");
        assertThat(OracleHypothesisSessionColumnMigrator.canonicalIdentifier(
                metadata, "idx_hyp_session"))
                .isEqualTo("IDX_HYP_SESSION");
    }

    @Test
    void preservesIdentifierCaseWhenDatabaseDoesNotFoldUnquotedNames()
            throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);

        assertThat(OracleHypothesisSessionColumnMigrator.canonicalIdentifier(
                metadata, "analysis_session_id"))
                .isEqualTo("analysis_session_id");
    }

    @Test
    void recognizesOnlyNationalCharacterSourceTypes() {
        assertThat(OracleHypothesisSessionColumnMigrator
                .isNationalCharacterType("NVARCHAR2")).isTrue();
        assertThat(OracleHypothesisSessionColumnMigrator
                .isNationalCharacterType("NCHAR")).isTrue();
        assertThat(OracleHypothesisSessionColumnMigrator
                .isNationalCharacterType("VARCHAR2")).isFalse();
        assertThat(OracleHypothesisSessionColumnMigrator
                .isNationalCharacterType(null)).isFalse();
    }
}
