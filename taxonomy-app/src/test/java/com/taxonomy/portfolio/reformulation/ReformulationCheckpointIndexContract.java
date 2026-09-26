package com.taxonomy.portfolio.reformulation;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** The same physical index contract for Hibernate-created and PostgreSQL-migrated schemas. */
public final class ReformulationCheckpointIndexContract {
    private static final Map<String, List<String>> EXPECTED = Map.of(
            "idx_reform_checkpoint_proposal", List.of("proposal_id", "scope_key"),
            "idx_reform_cp_run_order", List.of("run_id", "created_at", "id"),
            "idx_reform_cp_run_kind", List.of("run_id", "task_kind"));
    private static final Map<String, Boolean> EXPECTED_NON_UNIQUE = Map.of(
            "idx_reform_checkpoint_proposal", true,
            "idx_reform_cp_run_order", true,
            "idx_reform_cp_run_kind", true);

    private ReformulationCheckpointIndexContract() {}

    public static void verify(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String table = metadata.storesUpperCaseIdentifiers()
                    ? "REFORMULATION_NODE_CHECKPOINT" : "reformulation_node_checkpoint";
            var indexes = new LinkedHashMap<String, TreeMap<Integer, String>>();
            try (ResultSet rows = metadata.getIndexInfo(connection.getCatalog(), connection.getSchema(), table, false, false)) {
                while (rows.next()) {
                    String name = rows.getString("INDEX_NAME");
                    if (name == null || rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                        continue;
                    }
                    name = name.toLowerCase(Locale.ROOT);
                    if (!EXPECTED.containsKey(name)) {
                        continue;
                    }
                    require(rows.getBoolean("NON_UNIQUE") == EXPECTED_NON_UNIQUE.get(name),
                            "Unexpected uniqueness for index " + name);
                    String column = rows.getString("COLUMN_NAME");
                    require(column != null, "Unexpected expression index: " + name);
                    indexes.computeIfAbsent(name, ignored -> new TreeMap<>())
                            .put(rows.getInt("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
                }
            }
            EXPECTED.forEach((name, columns) -> {
                require(indexes.containsKey(name), "Missing checkpoint inspection index: " + name);
                require(List.copyOf(indexes.get(name).values()).equals(columns),
                        "Wrong ordered columns for " + name + ": " + indexes.get(name));
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
