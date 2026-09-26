package com.taxonomy.portfolio.reformulation;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Shared physical foreign-key/column contract for Hibernate and PostgreSQL Flyway. */
public final class ReformulationAdoptionSchemaContract {
    private ReformulationAdoptionSchemaContract() {}

    public static void verify(DataSource source) throws SQLException {
        try (var c = source.getConnection()) {
            columns(c, "reformulation_adoption_preview", List.of("id", "proposal_id", "scope_key", "content_hash", "preview_payload", "created_at"));
            columns(c, "reformulation_adoption", List.of("id", "proposal_id", "preview_id", "scope_key", "command_hash", "requirement_id", "target_version_id", "receipt_payload", "created_at"));
            fk(c, "reformulation_adoption_preview", "fk_reform_adopt_preview_offer", Set.of("proposal_id->reformulation_proposal.id", "scope_key->reformulation_proposal.scope_key"));
            fk(c, "reformulation_adoption", "fk_reform_adopt_preview", Set.of("preview_id->reformulation_adoption_preview.id", "proposal_id->reformulation_adoption_preview.proposal_id", "scope_key->reformulation_adoption_preview.scope_key"));
            fk(c, "reformulation_adoption", "fk_reform_adopt_version", Set.of("target_version_id->project_req_version.id", "requirement_id->project_req_version.requirement_id", "scope_key->project_req_version.scope_key"));
        }
    }

    private static String table(Connection c, String name) throws SQLException {
        return c.getMetaData().storesUpperCaseIdentifiers() ? name.toUpperCase(Locale.ROOT) : name;
    }

    private static void columns(Connection c, String name, List<String> expected) throws SQLException {
        Set<String> actual = new HashSet<>();
        try (var rows = c.getMetaData().getColumns(c.getCatalog(), c.getSchema(), table(c, name), null)) {
            while (rows.next()) {
                String column = rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                actual.add(column);
                if (expected.contains(column) && rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls) {
                    throw new AssertionError("Nullable adoption column: " + column);
                }
            }
        }
        if (!actual.containsAll(expected)) {
            throw new AssertionError("Missing adoption columns: " + name + " " + actual);
        }
    }

    private static void fk(Connection c, String name, String key, Set<String> expected) throws SQLException {
        Set<String> actual = new HashSet<>();
        try (var rows = c.getMetaData().getImportedKeys(c.getCatalog(), c.getSchema(), table(c, name))) {
            while (rows.next()) {
                if (key.equalsIgnoreCase(rows.getString("FK_NAME"))) {
                    actual.add((rows.getString("FKCOLUMN_NAME") + "->" + rows.getString("PKTABLE_NAME") + "." + rows.getString("PKCOLUMN_NAME")).toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!actual.equals(expected)) {
            throw new AssertionError("Wrong adoption FK " + key + ": " + actual);
        }
    }
}
