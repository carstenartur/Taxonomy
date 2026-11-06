package com.taxonomy.plugins.sparx;

import org.sqlite.JDBC;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;

/** The only SQL mutation boundary. Identifiers come from this allowlist, never from exchange data. */
final class QeaWriteDatabase implements AutoCloseable {
    private static final Map<String, Set<String>> WRITABLE = Map.of(
            "usys_system", Set.of("property", "value"),
            "t_package", Set.of("package_id", "name", "parent_id", "ea_guid", "notes", "tpos", "createddate", "modifieddate"),
            "t_object", Set.of("object_id", "object_type", "name", "note", "package_id", "stereotype", "ea_guid", "parentid", "pdata1", "createddate", "modifieddate"),
            "t_connector", Set.of("connector_id", "connector_type", "start_object_id", "end_object_id", "ea_guid", "name", "notes", "stereotype", "direction", "sourceisaggregate", "destisaggregate"),
            "t_diagram", Set.of("diagram_id", "package_id", "name", "notes", "diagram_type", "ea_guid", "createddate", "modifieddate"),
            "t_diagramobjects", Set.of("diagram_id", "object_id", "recttop", "rectleft", "rectright", "rectbottom", "sequence", "objectstyle", "instance_id"),
            "t_objectproperties", Set.of("propertyid", "object_id", "property", "value", "notes", "ea_guid"),
            "t_connectortag", Set.of("propertyid", "elementid", "property", "value", "notes", "ea_guid"),
            "t_xref", Set.of("xrefid", "name", "type", "client", "description"));
    private static final Set<String> CLOCK_COLUMNS = Set.of("createddate", "modifieddate");
    private static final Set<String> EMPTY_TEMPLATE_TABLES = Set.of("t_diagramlinks", "t_attribute", "t_attributeconstraints", "t_attributetag",
            "t_operation", "t_operationparams", "t_operationposts", "t_operationpres", "t_operationtag", "t_connectorconstraint",
            "t_objectconstraint", "t_objecteffort", "t_objectfiles", "t_objectmetrics", "t_objectproblems", "t_objectrequires",
            "t_objectresource", "t_objectrisks", "t_objectscenarios", "t_objecttests", "t_objecttrx", "t_document", "t_files");
    private record Column(String name, String type, boolean required, String defaultValue, int key, int hidden) { }
    private final Connection connection;
    private final Map<String, Map<String, Column>> tables = new HashMap<>();
    private final Map<String, Long> counters = new HashMap<>();
    private final Map<String, Long> floors = new HashMap<>();
    private final Map<String, Set<Long>> allocations = new HashMap<>();
    private final Map<String, Boolean> templateTables = new HashMap<>();
    private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    private boolean dirty;

    QeaWriteDatabase(Path copy) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setOpenMode(SQLiteOpenMode.OPEN_URI);
        config.enableLoadExtension(false);
        config.setBusyTimeout(1000);
        config.setPragma(SQLiteConfig.Pragma.LIMIT_ATTACHED, "0");
        config.setPragma(SQLiteConfig.Pragma.LIMIT_LENGTH, "1048576");
        config.setPragma(SQLiteConfig.Pragma.LIMIT_SQL_LENGTH, "262144");
        config.setPragma(SQLiteConfig.Pragma.LIMIT_COLUMN, "128");
        connection = new JDBC().connect("jdbc:sqlite:" + copy.toUri() + "?mode=rw", config.toProperties());
        try {
            ProgressHandler.setHandler(connection, 1000, new ProgressHandler() {
                private int calls;
                @Override protected int progress() { return ++calls > 50_000 || expired() ? 1 : 0; }
            });
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA trusted_schema=OFF");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA journal_mode=DELETE");
                statement.execute("PRAGMA synchronous=FULL");
                try (var result = statement.executeQuery("PRAGMA page_size")) {
                    if (!result.next() || result.getInt(1) < 512) throw fail("QEA_SCHEMA_UNSUPPORTED", "Unsupported SQLite page size");
                    int pages = QeaDatabase.MAX_BYTES / result.getInt(1);
                    try (var limit = connection.createStatement()) { limit.execute("PRAGMA max_page_count=" + pages); }
                }
            }
            schema();
            connection.setAutoCommit(false);
        } catch (SQLException | RuntimeException failure) {
            try { connection.close(); } catch (SQLException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    private void schema() throws SQLException {
        int entries = 0;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT name,type,sql FROM sqlite_schema LIMIT 513")) {
            while (rows.next()) {
                budget(); if (++entries > 512) throw fail("QEA_LIMIT", "The writable SQLite schema exceeds 512 entries");
                String kind = rows.getString(2), name = rows.getString(1).toLowerCase(Locale.ROOT), sql = Objects.toString(rows.getString(3), "");
                if (kind.equals("trigger")) throw fail("QEA_SCHEMA_UNSUPPORTED", "QEA copies with database triggers cannot be written safely");
                if (EMPTY_TEMPLATE_TABLES.contains(name)) templateTables.put(name, kind.equals("table") && !sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE VIRTUAL TABLE"));
                if (!WRITABLE.containsKey(name)) continue;
                if (!kind.equals("table") || sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE VIRTUAL TABLE"))
                    throw fail("QEA_SCHEMA_UNSUPPORTED", "Writable QEA sources must be ordinary tables");
                if (tables.put(name, columns(name)) != null) throw fail("QEA_SCHEMA_UNSUPPORTED", "Ambiguous writable table identity");
            }
        }
        // Cascades can mutate unknown rows even without a trigger. The native EA template has no such actions.
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT 1 FROM sqlite_schema s JOIN pragma_foreign_key_list(s.name) f WHERE s.type='table' AND upper(f.on_update) NOT IN ('NO ACTION','RESTRICT') LIMIT 1")) {
            if (rows.next()) throw fail("QEA_SCHEMA_UNSUPPORTED", "Foreign-key update actions can change unreviewed native data");
        }
    }
    private Map<String, Column> columns(String table) throws SQLException {
        var columns = new LinkedHashMap<String, Column>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_xinfo(\"" + table + "\")")) {
            while (rows.next()) {
                if (columns.size() == 128) throw fail("QEA_LIMIT", "A writable table exceeds 128 columns");
                String name = rows.getString("name").toLowerCase(Locale.ROOT);
                var column = new Column(name, Objects.toString(rows.getString("type"), ""), rows.getInt("notnull") != 0,
                        rows.getString("dflt_value"), rows.getInt("pk"), rows.getInt("hidden"));
                if (column.hidden() != 0) throw fail("QEA_SCHEMA_UNSUPPORTED", "Generated or hidden columns can change unreviewed native data");
                columns.put(name, column);
            }
        }
        return columns;
    }

    boolean hasColumn(String table, String column) { return tables.getOrDefault(table, Map.of()).containsKey(column); }

    long next(String table, String id) throws SQLException {
        allowed(table, id); required(table, Set.of(id));
        String key = table + ":" + id;
        Long previous = counters.get(key);
        if (previous == null) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT coalesce(max(\"" + id + "\"),0) FROM \"" + table + "\"")) {
                rows.next(); previous = rows.getLong(1);
            }
            // AUTOINCREMENT tables may have a higher historical allocation than their surviving rows.
            try (var exists = connection.createStatement(); var found = exists.executeQuery("SELECT 1 FROM sqlite_schema WHERE type='table' AND name='sqlite_sequence'")) {
                if (found.next()) try (var sequence = connection.prepareStatement("SELECT seq FROM sqlite_sequence WHERE lower(name)=?")) {
                    sequence.setString(1, table);
                    try (var rows = sequence.executeQuery()) { while (rows.next()) previous = Math.max(previous, rows.getLong(1)); }
                }
            }
            floors.put(key, previous);
        }
        if (previous < 0 || previous >= Integer.MAX_VALUE) throw fail("QEA_LIMIT", "Native integer identities have exhausted the supported range");
        counters.put(key, previous + 1); allocations.computeIfAbsent(key, ignored -> new HashSet<>()).add(previous + 1); return previous + 1;
    }

    long allocate(String table, String id, String frozen) throws SQLException {
        if (frozen == null || frozen.isBlank()) return next(table, id);
        long number;
        try { number = Long.parseLong(frozen); if (number <= 0 || number > Integer.MAX_VALUE) throw new NumberFormatException(); }
        catch (NumberFormatException invalid) { throw fail("QEA_REFERENCE_INVALID", "A frozen native row identity must be a positive bounded integer"); }
        String key = table + ":" + id;
        if (!floors.containsKey(key)) {
            long tentative = next(table, id); allocations.get(key).remove(tentative); counters.put(key, tentative - 1);
        }
        if (number <= floors.get(key) || !allocations.computeIfAbsent(key, ignored -> new HashSet<>()).add(number))
            throw fail("QEA_DUPLICATE_IDENTITY", "A frozen new native row ID collides with an existing or allocated identity");
        counters.put(key, Math.max(counters.get(key), number)); return number;
    }

    void validateEmptyTemplate() throws SQLException {
        for (String table : EMPTY_TEMPLATE_TABLES) {
            if (!templateTables.containsKey(table)) continue;
            if (!templateTables.get(table)) throw fail("QEA_SCHEMA_UNSUPPORTED", "Native template model sources must be ordinary tables");
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT 1 FROM \"" + table + "\" LIMIT 1")) {
                if (rows.next()) throw fail("QEA_TEMPLATE_NOT_EMPTY", "The template contains native model references or attached model data");
            }
        }
    }

    void insert(String table, Map<String, Object> supplied) throws SQLException {
        budget();
        var values = new TreeMap<>(supplied);
        required(table, values.keySet());
        for (Column column : tables.get(table).values()) {
            if (values.containsKey(column.name())) continue;
            if (CLOCK_COLUMNS.contains(column.name()) && WRITABLE.get(table).contains(column.name())) {
                if (column.required()) throw fail("QEA_SCHEMA_UNSUPPORTED", "A mandatory native timestamp needs an explicit frozen export-time policy");
                values.put(column.name(), null); // No wall-clock defaults: a replay is the same reviewed file.
            } else {
                if (column.required() && column.defaultValue() == null)
                    throw fail("QEA_SCHEMA_UNSUPPORTED", "A required native insert column has no declared value or default");
                if (column.defaultValue() != null && !constantDefault(column.defaultValue()))
                    throw fail("QEA_SCHEMA_UNSUPPORTED", "An unknown non-constant default cannot be executed while creating model rows");
            }
        }
        values.keySet().forEach(column -> allowed(table, column));
        String names = values.keySet().stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(","));
        String markers = String.join(",", Collections.nCopies(values.size(), "?"));
        try (var statement = connection.prepareStatement("INSERT INTO \"" + table + "\" (" + names + ") VALUES (" + markers + ")")) {
            int index = 0; for (Object value : values.values()) statement.setObject(++index, value);
            if (statement.executeUpdate() != 1) throw fail("QEA_WRITE_CONFLICT", "Native insertion did not create exactly one row");
            dirty = true;
        }
    }
    private static boolean constantDefault(String value) {
        String text = value.strip();
        while (text.startsWith("(") && text.endsWith(")")) text = text.substring(1, text.length() - 1).strip();
        return text.equalsIgnoreCase("NULL") || text.matches("[+-]?[0-9]+(?:\\.[0-9]+)?")
                || text.matches("'(?:[^']|'')*'") || text.matches("[xX]'[0-9A-Fa-f]*'");
    }

    void update(String table, String key, Object id, Map<String, Object> values) throws SQLException {
        if (values.isEmpty()) return;
        budget(); allowed(table, key); required(table, values.keySet()); required(table, Set.of(key));
        var sorted = new TreeMap<>(values);
        String setters = sorted.keySet().stream().map(c -> "\"" + c + "\"=?").collect(Collectors.joining(","));
        try (var statement = connection.prepareStatement("UPDATE \"" + table + "\" SET " + setters + " WHERE \"" + key + "\"=?")) {
            int index = 0; for (Object value : sorted.values()) statement.setObject(++index, value);
            statement.setObject(++index, id);
            if (statement.executeUpdate() != 1) throw fail("QEA_WRITE_CONFLICT", "A native update must identify exactly one existing row");
            dirty = true;
        }
    }

    void required(String table, Set<String> columns) {
        for (String column : columns) allowed(table, column);
        if (!tables.containsKey(table) || !tables.get(table).keySet().containsAll(columns))
            throw fail("QEA_SCHEMA_UNSUPPORTED", "The native schema lacks columns required for a selected change in " + table);
    }
    private static void allowed(String table, String column) {
        if (!WRITABLE.getOrDefault(table, Set.of()).contains(column)) throw new IllegalStateException("Undeclared QEA mutation column");
    }

    void commit() throws SQLException {
        budget();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA quick_check(1)")) {
            if (!rows.next() || !"ok".equals(rows.getString(1))) throw fail("QEA_FILE_INVALID", "Native output failed SQLite integrity validation");
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (rows.next()) throw fail("QEA_SELECTION_DEPENDENCY", "Native output violates a declared reference constraint");
        }
        connection.commit();
    }
    boolean dirty() { return dirty; }
    private boolean expired() { return System.nanoTime() > deadline || Thread.currentThread().isInterrupted(); }
    void budget() { if (expired()) throw fail("QEA_LIMIT", "Native QEA writing exceeded its bounded work budget"); }
    @Override public void close() throws SQLException { connection.close(); }
}
