package com.taxonomy.backup.recovery;

import java.sql.Connection;
import java.sql.SQLException;

/** Metadata evidence for the application-owned Oracle acceptance fixtures. */
public final class OracleRecoveryMetadata {
    private OracleRecoveryMetadata() { }

    public record IndexColumn(String physicalColumn, String expression, String jdbcDirection, String dictionaryDirection) {
        public String resolvedColumn() {
            if (!"ASC".equals(dictionaryDirection) && !"DESC".equals(dictionaryDirection)) {
                throw new IllegalArgumentException("Oracle dictionary index direction is unavailable");
            }
            String column = physicalColumn;
            if (column.startsWith("SYS_NC")) {
                if (("SYS_OP_DESCEND(\"ID\")".equals(expression) || "\"ID\"".equals(expression))
                        && "DESC".equals(dictionaryDirection)) column = "ID";
                if ("SYS_EXTRACT_UTC(\"WRITE_LEASE_UNTIL\")".equals(expression)) column = "WRITE_LEASE_UNTIL";
            }
            return column + ("DESC".equals(dictionaryDirection) ? " DESC" : "");
        }
    }

    public static IndexColumn indexColumn(Connection connection, String table, String index, int position,
                                          String physicalColumn, String jdbcDirection) throws SQLException {
        String expression = null;
        if (physicalColumn.startsWith("SYS_NC")) {
            try (var query = connection.prepareStatement("SELECT column_expression FROM user_ind_expressions "
                    + "WHERE table_name = ? AND index_name = ? AND column_position = ?")) {
                query.setString(1, table); query.setString(2, index); query.setInt(3, position);
                try (var result = query.executeQuery()) { if (result.next()) expression = result.getString(1); }
            }
        }
        // Pinned Oracle JDBC returns NULL for ASC_OR_DESC. The dictionary owns
        // the actual direction, independently of that unsupported JDBC field.
        String direction;
        try (var query = connection.prepareStatement("SELECT descend FROM user_ind_columns "
                + "WHERE table_name = ? AND index_name = ? AND column_position = ?")) {
            query.setString(1, table); query.setString(2, index); query.setInt(3, position);
            try (var result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Oracle dictionary index direction is unavailable");
                direction = result.getString(1);
                if (result.next()) throw new SQLException("Oracle dictionary index position is ambiguous");
            }
        }
        return new IndexColumn(physicalColumn, expression, jdbcDirection, direction);
    }

    public static String serverVersion(Connection connection) throws SQLException {
        String version = connection.getMetaData().getDatabaseProductVersion();
        if (version == null || version.isBlank()) throw new SQLException("Oracle server version is unavailable");
        return version;
    }
}
