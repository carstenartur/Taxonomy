package com.taxonomy.shared.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** The same enabled foreign-key contract for repository and hypothesis migrations. */
final class ForeignKeyMetadata {
    private ForeignKeyMetadata() { }

    static boolean matches(Connection connection, String sourceCatalog, String sourceSchema,
            String sourceTable, String targetSchema, String targetTable,
            List<String> sourceColumns, List<String> targetColumns) throws SQLException {
        Map<String, Map<Short, String>> keys = new LinkedHashMap<>();
        if (connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("oracle")) {
            // Oracle JDBC imported-key metadata filters parent constraints to P,
            // omitting foreign keys to UNIQUE natural repository and hypothesis keys.
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT f.constraint_name, fc.position, fc.column_name, pc.column_name
                    FROM all_constraints f
                    JOIN all_constraints p ON p.owner=f.r_owner AND p.constraint_name=f.r_constraint_name
                    JOIN all_cons_columns fc ON fc.owner=f.owner AND fc.constraint_name=f.constraint_name
                      AND fc.table_name=f.table_name
                    JOIN all_cons_columns pc ON pc.owner=p.owner AND pc.constraint_name=p.constraint_name
                      AND pc.table_name=p.table_name AND pc.position=fc.position
                    WHERE f.constraint_type='R' AND f.status='ENABLED' AND p.constraint_type IN ('P','U')
                      AND f.owner=? AND f.table_name=? AND p.owner=? AND p.table_name=?
                    """)) {
                query.setString(1, sourceSchema);
                query.setString(2, sourceTable);
                query.setString(3, targetSchema);
                query.setString(4, targetTable);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) keys.computeIfAbsent(result.getString(1), unused -> new TreeMap<>())
                            .put(result.getShort(2), result.getString(3).toLowerCase(Locale.ROOT)
                                    + ":" + result.getString(4).toLowerCase(Locale.ROOT));
                }
            }
        } else {
            try (ResultSet result = connection.getMetaData().getImportedKeys(sourceCatalog, sourceSchema, sourceTable)) {
                while (result.next()) {
                    if (!targetTable.equalsIgnoreCase(result.getString("PKTABLE_NAME"))
                            || !Objects.equals(targetSchema, result.getString("PKTABLE_SCHEM"))) continue;
                    keys.computeIfAbsent(result.getString("FK_NAME"), unused -> new TreeMap<>())
                            .put(result.getShort("KEY_SEQ"), result.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT)
                                    + ":" + result.getString("PKCOLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < sourceColumns.size(); i++) expected.add(sourceColumns.get(i) + ":" + targetColumns.get(i));
        // Hibernate may order equivalent pairs differently. Retain the complete
        // mapping, cardinality, referenced owner and each source/target pairing.
        return keys.values().stream().anyMatch(key -> key.size() == expected.size()
                && new LinkedHashSet<>(key.values()).equals(new LinkedHashSet<>(expected)));
    }
}
