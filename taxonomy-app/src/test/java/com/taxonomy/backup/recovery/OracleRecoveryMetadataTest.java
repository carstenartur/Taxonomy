package com.taxonomy.backup.recovery;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OracleRecoveryMetadataTest {
    @Test void nullJdbcDirectionUsesTheExactOracleDictionaryDirectionAndPosition() throws Exception {
        var fixture = index("DESC", "\"ID\"");
        var column = OracleRecoveryMetadata.indexColumn(fixture.connection(), "GIT_REFLOG", "IDX_REFLOG_REPO_REF_KEY_ID", 3, "SYS_NC00013$", null);
        assertThat(column.jdbcDirection()).isNull();
        assertThat(column.dictionaryDirection()).isEqualTo("DESC");
        assertThat(column.resolvedColumn()).isEqualTo("ID DESC");
        verify(fixture.direction()).setString(1, "GIT_REFLOG");
        verify(fixture.direction()).setString(2, "IDX_REFLOG_REPO_REF_KEY_ID");
        verify(fixture.direction()).setInt(3, 3);
    }

    @Test void aJdbcDescendingHintCannotTurnAnAscendingDictionaryColumnIntoDescendingId() throws Exception {
        var fixture = index("ASC", "\"ID\"");
        var column = OracleRecoveryMetadata.indexColumn(fixture.connection(), "GIT_REFLOG", "IDX_REFLOG_REPO_REF_KEY_ID", 3, "SYS_NC00013$", "D");
        assertThat(column.dictionaryDirection()).isEqualTo("ASC");
        assertThat(column.resolvedColumn()).isNotEqualTo("ID DESC");
    }

    @Test void unknownDictionaryDirectionCannotPassAsARequiredIndex() throws Exception {
        var fixture = index("UNKNOWN", "\"ID\"");
        assertThatThrownBy(() -> OracleRecoveryMetadata.indexColumn(fixture.connection(), "GIT_REFLOG", "IDX_REFLOG_REPO_REF_KEY_ID", 3, "SYS_NC00013$", "D").resolvedColumn())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void missingDictionaryRowsCannotBeReplacedWithAJdbcHint() throws Exception {
        var fixture = index("DESC", "\"ID\"");
        when(fixture.directionRows().next()).thenReturn(false);
        assertThatThrownBy(() -> OracleRecoveryMetadata.indexColumn(fixture.connection(), "GIT_REFLOG", "IDX_REFLOG_REPO_REF_KEY_ID", 3, "SYS_NC00013$", "D"))
                .isInstanceOf(SQLException.class);
    }

    @Test void anUnrelatedFunctionCannotMasqueradeAsTheIndexedId() throws Exception {
        var fixture = index("DESC", "ABS(\"ID\")");
        assertThat(OracleRecoveryMetadata.indexColumn(fixture.connection(), "GIT_REFLOG", "IDX_REFLOG_REPO_REF_KEY_ID", 3, "SYS_NC00013$", "D").resolvedColumn())
                .isEqualTo("SYS_NC00013$ DESC");
    }

    @Test void onlyTheExactUtcLeaseExpressionResolvesToTheRequiredLeaseColumn() throws Exception {
        for (String expression : new String[]{"SYS_EXTRACT_UTC(\"WRITE_LEASE_UNTIL\")", "SYS_EXTRACT_UTC(\"OTHER_COLUMN\")"}) {
            var fixture = index("ASC", expression);
            assertThat(OracleRecoveryMetadata.indexColumn(fixture.connection(), "GIT_PACKS", "IDX_PACK_REPO_LEASE", 3, "SYS_NC00019$", "A").resolvedColumn())
                    .isEqualTo(expression.contains("OTHER_COLUMN") ? "SYS_NC00019$" : "WRITE_LEASE_UNTIL");
        }
    }

    @Test void aChangedProductBrandDoesNotHideTheActualMetadataVersion() throws Exception {
        String version = "Oracle AI Database 26ai Free\nVersion 23.26.3.0.0"; // Authored branding regression fixture.
        assertThat(OracleRecoveryMetadata.serverVersion(versionConnection(version))).isEqualTo(version);
    }

    @Test void anUnavailableMetadataVersionFailsRatherThanInventingARelease() throws Exception {
        for (String version : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> OracleRecoveryMetadata.serverVersion(versionConnection(version))).isInstanceOf(SQLException.class);
        }
    }

    private record IndexFixture(Connection connection, PreparedStatement direction, ResultSet directionRows) { }

    private static IndexFixture index(String direction, String expression) throws Exception {
        var connection = mock(Connection.class);
        var directionQuery = mock(PreparedStatement.class); var directionRows = mock(ResultSet.class);
        when(connection.prepareStatement(contains("user_ind_columns"))).thenReturn(directionQuery);
        when(directionQuery.executeQuery()).thenReturn(directionRows);
        when(directionRows.next()).thenReturn(true, false);
        when(directionRows.getString(1)).thenReturn(direction);
        var expressionQuery = mock(PreparedStatement.class); var expressionRows = mock(ResultSet.class);
        when(connection.prepareStatement(contains("user_ind_expressions"))).thenReturn(expressionQuery);
        when(expressionQuery.executeQuery()).thenReturn(expressionRows);
        when(expressionRows.next()).thenReturn(true, false);
        when(expressionRows.getString(1)).thenReturn(expression);
        return new IndexFixture(connection, directionQuery, directionRows);
    }

    private static Connection versionConnection(String version) throws Exception {
        var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductVersion()).thenReturn(version);
        var statement = mock(Statement.class); var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(false); // The old product-name filter returned no row on actual Oracle.
        return connection;
    }
}
