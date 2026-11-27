package com.taxonomy.plugins.sparx;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;

/** Native row references that a newly allocated ID must not silently resolve. */
final class QeaReferencedIds {
    private static final String OBJECT = "t_object:object_id";
    private static final String PACKAGE = "t_package:package_id";
    private static final String DIAGRAM = "t_diagram:diagram_id";
    private static final String CONNECTOR = "t_connector:connector_id";

    private QeaReferencedIds() { }

    static void reserveSnapshot(QeaDatabase.Snapshot basis, Map<String, Set<Long>> used) {
        var objects = used.computeIfAbsent(OBJECT, ignored -> new HashSet<>());
        var packages = used.computeIfAbsent(PACKAGE, ignored -> new HashSet<>());
        var diagrams = used.computeIfAbsent(DIAGRAM, ignored -> new HashSet<>());
        var connectors = used.computeIfAbsent(CONNECTOR, ignored -> new HashSet<>());
        for (var row : basis.rows("t_package")) packages.add(row.number("parent_id"));
        for (var row : basis.rows("t_object")) objects.add(row.number("parentid"));
        for (var row : basis.rows("t_diagram")) packages.add(row.number("package_id"));
        for (var row : basis.rows("t_connector")) {
            objects.add(row.number("start_object_id"));
            objects.add(row.number("end_object_id"));
        }
        for (var row : basis.rows("t_diagramobjects")) {
            objects.add(row.number("object_id"));
            diagrams.add(row.number("diagram_id"));
        }
        for (var row : basis.rows("t_diagramlinks")) {
            diagrams.add(row.number("diagramid"));
            connectors.add(row.number("connectorid"));
        }
        for (var row : basis.rows("t_objectproperties")) objects.add(row.number("object_id"));
        for (var row : basis.rows("t_connectortag")) connectors.add(row.number("elementid"));
    }

    // These native fields are not retained by every bounded projection. Read only numeric references
    // above the historical allocation floor, without projecting opaque attachment rows or free text.
    static void reserveAdditional(Connection db, String target, long floor, Set<Long> used) throws SQLException {
        switch (target) {
            case OBJECT -> {
                for (String table : List.of("t_attribute", "t_attributeconstraints", "t_operation", "t_method",
                        "t_objectconstraint", "t_objecteffort", "t_objectfiles", "t_objectmetrics", "t_objectproblems",
                        "t_objectrequires", "t_objectresource", "t_objectrisks", "t_objectscenarios", "t_objecttests", "t_objecttrx"))
                    reserveColumn(db, table, "object_id", floor, used);
                for (String table : List.of("t_object", "t_attribute", "t_operation", "t_operationparams"))
                    reserveColumn(db, table, "classifier", floor, used);
                reserveColumn(db, "t_diagram", "parentid", floor, used);
            }
            case PACKAGE -> {
                reserveColumn(db, "t_object", "package_id", floor, used);
                reserveColumn(db, "t_object", "pdata1", floor, used);
                reserveColumn(db, "t_diagramtypes", "package_id", floor, used);
                reserveColumn(db, "t_rtfreport", "rootpackage", floor, used);
            }
            case DIAGRAM -> {
                reserveColumn(db, "t_object", "diagram_id", floor, used);
                reserveColumn(db, "t_connector", "diagramid", floor, used);
            }
            case CONNECTOR -> {
                reserveColumn(db, "t_connectorconstraint", "connectorid", floor, used);
                reserveColumn(db, "t_roleconstraint", "connectorid", floor, used);
            }
            default -> { /* No known references to auxiliary row IDs. */ }
        }
    }

    private static void reserveColumn(Connection db, String table, String column, long floor, Set<Long> used) throws SQLException {
        try (var schema = db.prepareStatement("SELECT type, sql FROM sqlite_schema WHERE lower(name)=? AND type IN ('table','view')")) {
            schema.setString(1, table);
            try (var rows = schema.executeQuery()) {
                if (!rows.next()) return;
                if (!"table".equals(rows.getString(1)) || rows.getString(2).stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE VIRTUAL TABLE"))
                    throw fail("QEA_SCHEMA_UNSUPPORTED", "Native reference sources must be ordinary tables: " + table);
            }
        }
        try (var columns = db.prepareStatement("SELECT hidden FROM pragma_table_xinfo(?) WHERE lower(name)=?")) {
            columns.setString(1, table);
            columns.setString(2, column);
            try (var rows = columns.executeQuery()) {
                if (!rows.next()) return;
                if (rows.getInt(1) != 0) throw fail("QEA_SCHEMA_UNSUPPORTED", "A native reference column must be a stored ordinary column");
            }
        }
        // PDATA1 is a package reference only on package mirrors. Other object types can use it for unrelated data.
        String predicate = column.equals("pdata1") ? "object_type COLLATE BINARY = 'Package' AND pdata1 NOT GLOB '*[^0-9]*' AND " : "";
        String numeric = "CAST(" + column + " AS NUMERIC)";
        String query = "SELECT DISTINCT " + numeric + " FROM " + table + " WHERE " + predicate
                + numeric + " = " + column + " AND " + numeric + " = CAST(" + numeric + " AS INTEGER)"
                + " AND " + numeric + " BETWEEN ? AND 2147483647 LIMIT ?";
        try (var statement = db.prepareStatement(query)) {
            statement.setLong(1, floor + 1);
            statement.setInt(2, QeaDatabase.MAX_ROWS + 1);
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    if (++count > QeaDatabase.MAX_ROWS) throw fail("QEA_LIMIT", "Too many distinct native references in " + table + "." + column);
                    used.add(rows.getLong(1));
                }
            }
        }
    }
}
