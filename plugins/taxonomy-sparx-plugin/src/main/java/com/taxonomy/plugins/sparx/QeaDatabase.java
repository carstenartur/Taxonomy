package com.taxonomy.plugins.sparx;

import org.sqlite.JDBC;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;

/** Owns only an isolated temporary snapshot. No source path or caller-provided SQL is accepted. */
final class QeaDatabase {
    static final int MAX_BYTES = 32 * 1024 * 1024;
    static final int MAX_ROWS = 20_000;
    static final int MAX_CELL = 65_536;
    static final int MAX_TEXT = 16 * 1024 * 1024;
    static final int MAX_DEPTH = 128;
    private static final byte[] MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final List<String> TABLES = List.of("usys_system", "t_package", "t_object", "t_connector",
            "t_diagram", "t_diagramobjects", "t_objectproperties", "t_connectortag", "t_xref");
    private static final List<String> ADMBW_TABLES = List.of("usys_system", "t_package", "t_object",
            "t_connector", "t_diagram", "t_diagramobjects", "t_objectproperties", "t_connectortag", "t_xref");
    private static final Map<String, List<String>> ADMBW_COLUMNS = Map.of(
            "usys_system", List.of("property", "value"),
            "t_package", List.of("package_id", "parent_id", "name", "ea_guid"),
            "t_object", List.of("object_id", "object_type", "ea_guid", "parentid", "stereotype"),
            "t_connector", List.of("connector_id", "start_object_id", "end_object_id", "ea_guid"),
            "t_diagram", List.of("diagram_id", "package_id", "name", "ea_guid"),
            "t_diagramobjects", List.of("object_id"),
            "t_objectproperties", List.of("propertyid", "object_id", "property", "ea_guid"),
            "t_connectortag", List.of("elementid"),
            "t_xref", List.of("xrefid", "name", "type", "client", "description"));
    private static final Set<String> REQUIRED = Set.of("usys_system", "t_package", "t_object", "t_connector");
    private static final Map<String, Set<String>> COLUMNS = Map.of(
            "usys_system", Set.of("property", "value"),
            "t_package", Set.of("package_id", "parent_id", "name", "ea_guid"),
            "t_object", Set.of("object_id", "object_type", "package_id", "name", "ea_guid"),
            "t_connector", Set.of("connector_id", "connector_type", "start_object_id", "end_object_id", "ea_guid"),
            "t_diagram", Set.of("diagram_id", "package_id", "name", "ea_guid"),
            "t_diagramobjects", Set.of("diagram_id", "object_id"),
            "t_objectproperties", Set.of("propertyid", "object_id", "property", "value"),
            "t_connectortag", Set.of("propertyid", "elementid", "property", "value"),
            "t_xref", Set.of("xrefid", "name", "type", "client", "description"));

    record Row(String table, Map<String, String> values, Set<String> binaryFields) {
        String text(String field) { return values.getOrDefault(field, ""); }
        long number(String field) {
            String value = text(field);
            if (value.isEmpty()) return 0;
            try {
                long result = Long.parseLong(value);
                if (result < 0 || result > Integer.MAX_VALUE) throw new NumberFormatException();
                return result;
            } catch (NumberFormatException invalid) { throw fail("QEA_REFERENCE_INVALID", "Invalid non-negative EA row reference in " + table + "." + field); }
        }
        long id(String field) {
            long id = number(field);
            if (id == 0) throw fail("QEA_REFERENCE_INVALID", "Missing positive local row ID in " + table + "." + field);
            return id;
        }
        Map<String, String> evidence() {
            var result = new TreeMap<String, String>();
            values.forEach((key, value) -> result.put("ea:" + table + ":" + key, value));
            return result;
        }
    }
    record Snapshot(Map<String, List<Row>> tables, List<String> omittedTables, String sha256, int bytes) {
        List<Row> rows(String table) { return tables.getOrDefault(table, List.of()); }
    }
    private record Schema(String kind, String sql) { }

    private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    private int rows;
    private int textCharacters;
    private boolean interrupted;

    Snapshot read(byte[] bytes) { return read(bytes, false); }

    Snapshot readAdmbw(byte[] bytes) { return read(bytes, true); }

    private Snapshot read(byte[] bytes, boolean admbw) {
        List<String> selectedTables = admbw ? ADMBW_TABLES : TABLES;
        if (bytes.length > MAX_BYTES) throw fail("QEA_LIMIT", "The QEA upload exceeds 32 MiB");
        if (bytes.length < 512 || !Arrays.equals(MAGIC, Arrays.copyOf(bytes, MAGIC.length)))
            throw fail("QEA_FILE_INVALID", "Upload a complete SQLite QEA snapshot with a valid database header");
        Path snapshot = null;
        try {
            snapshot = Files.createTempFile("taxonomy-qea-", ".sqlite");
            Files.write(snapshot, bytes);
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            config.setOpenMode(SQLiteOpenMode.OPEN_URI);
            config.enableLoadExtension(false);
            config.setBusyTimeout(1000);
            config.setPragma(SQLiteConfig.Pragma.LIMIT_ATTACHED, "0");
            config.setPragma(SQLiteConfig.Pragma.LIMIT_LENGTH, "1048576");
            config.setPragma(SQLiteConfig.Pragma.LIMIT_SQL_LENGTH, "262144");
            config.setPragma(SQLiteConfig.Pragma.LIMIT_COLUMN, "128");
            try (Connection connection = new JDBC().connect("jdbc:sqlite:" + snapshot.toUri() + "?mode=ro&immutable=1", config.toProperties())) {
                ProgressHandler.setHandler(connection, 1000, new ProgressHandler() {
                    private int calls;
                    @Override protected int progress() {
                        interrupted = ++calls > 50_000 || System.nanoTime() > deadline || Thread.currentThread().isInterrupted();
                        return interrupted ? 1 : 0;
                    }
                });
                try (var statement = connection.createStatement()) {
                    statement.execute("PRAGMA query_only=ON");
                    statement.execute("PRAGMA trusted_schema=OFF");
                }
                Map<String, Schema> schema = schema(connection);
                for (String table : selectedTables) {
                    Schema entry = schema.get(table);
                    if (entry == null && REQUIRED.contains(table)) throw fail(table.equals("usys_system") ? "QEA_MODEL_IDENTITY" : "QEA_SCHEMA_UNSUPPORTED",
                            "Missing required QEA table " + table + (table.equals("usys_system") ? " (Property/Value with ProjectGUID)" : ""));
                    if (entry != null && (!entry.kind().equals("table") || entry.sql().stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE VIRTUAL TABLE")))
                        throw fail("QEA_SCHEMA_UNSUPPORTED", "Supported QEA source " + table + " must be an ordinary table");
                }
                try (var statement = connection.createStatement(); var check = statement.executeQuery("PRAGMA quick_check(1)")) {
                    if (!check.next() || !"ok".equals(check.getString(1))) throw fail("QEA_FILE_INVALID", "SQLite integrity check failed for the uploaded snapshot");
                }
                Map<String, List<Row>> tables = new LinkedHashMap<>();
                for (String table : selectedTables) if (schema.containsKey(table))
                    tables.put(table, table(connection, table, admbw));
                List<String> omitted = schema.keySet().stream().filter(t -> !selectedTables.contains(t) && !t.startsWith("sqlite_"))
                        .filter(t -> Set.of("table", "view").contains(schema.get(t).kind())).sorted().toList();
                return new Snapshot(Map.copyOf(tables), omitted, digest(bytes), bytes.length);
            }
        } catch (SQLException invalid) {
            if (interrupted || System.nanoTime() > deadline) throw fail("QEA_LIMIT", "SQLite read exceeded the bounded work budget");
            throw fail("QEA_FILE_INVALID", "The uploaded SQLite snapshot is corrupt, incomplete, or outside the supported QEA schema");
        } catch (IOException unavailable) { throw fail("QEA_READ_UNAVAILABLE", "Unable to read an isolated temporary QEA snapshot"); }
        finally {
            if (snapshot != null) try { Files.deleteIfExists(snapshot); }
            catch (IOException cleanup) { throw fail("QEA_READ_UNAVAILABLE", "Unable to remove the owned temporary QEA snapshot"); }
        }
    }

    private Map<String, Schema> schema(Connection connection) throws SQLException {
        var result = new TreeMap<String, Schema>();
        int count = 0;
        try (var statement = connection.createStatement(); var found = statement.executeQuery("SELECT name,type,sql FROM sqlite_schema LIMIT 513")) {
            while (found.next()) {
                budget();
                if (++count > 512) throw fail("QEA_LIMIT", "The SQLite schema exceeds 512 entries");
                String name = bounded(found.getString(1));
                String kind = bounded(found.getString(2));
                String sql = bounded(found.getString(3));
                if (Set.of("table", "view").contains(kind) && result.put(name.toLowerCase(Locale.ROOT), new Schema(kind, sql)) != null)
                    throw fail("QEA_SCHEMA_UNSUPPORTED", "The QEA schema has duplicate case-insensitive table names");
            }
        }
        return result;
    }

    private List<Row> table(Connection connection, String table, boolean admbw) throws SQLException {
        var result = new ArrayList<Row>();
        // Native delivery retains structural fields and orphan IDs that new rows could otherwise reuse.
        Collection<String> required = admbw ? ADMBW_COLUMNS.get(table) : COLUMNS.get(table);
        // table is chosen exclusively from TABLES or ADMBW_TABLES.
        String from = " FROM \"" + table + "\"";
        String query = !admbw ? "SELECT *" + from : switch (table) {
            case "t_xref" -> "SELECT xrefid, name, type, client, CASE WHEN lower(name) = 'stereotypes' THEN description END AS description" + from;
            case "usys_system" -> "SELECT property, CASE WHEN lower(property) = 'projectguid' THEN value END AS value" + from;
            case "t_diagramobjects" -> """
                    SELECT DISTINCT placement.object_id FROM t_diagramobjects placement
                    WHERE placement.object_id IS NOT NULL AND NOT EXISTS (
                        SELECT 1 FROM t_object existing WHERE existing.object_id = placement.object_id
                    )
                    """;
            case "t_connectortag" -> """
                    SELECT DISTINCT tag.elementid FROM t_connectortag tag
                    WHERE tag.elementid IS NOT NULL AND NOT EXISTS (
                        SELECT 1 FROM t_connector existing WHERE existing.connector_id = tag.elementid
                    )
                    """;
            default -> "SELECT " + String.join(", ", required) + from;
        };
        try (var statement = connection.createStatement(); var found = statement.executeQuery(query + " LIMIT " + (MAX_ROWS + 1))) {
            var metadata = found.getMetaData();
            if (metadata.getColumnCount() > 128) throw fail("QEA_LIMIT", "A supported QEA table exceeds 128 columns");
            var columns = new ArrayList<String>();
            for (int i = 1; i <= metadata.getColumnCount(); i++) columns.add(bounded(metadata.getColumnName(i)).toLowerCase(Locale.ROOT));
            if (!new HashSet<>(columns).containsAll(required)) throw fail("QEA_SCHEMA_UNSUPPORTED", "Required columns are missing from " + table);
            while (found.next()) {
                budget();
                if (++rows > MAX_ROWS) throw fail("QEA_LIMIT", "Supported QEA rows exceed 20000; no partial result is returned");
                var values = new TreeMap<String, String>();
                var binaries = new HashSet<String>();
                for (int i = 1; i <= columns.size(); i++) {
                    Object raw = found.getObject(i);
                    if (raw == null) continue;
                    String name = columns.get(i - 1);
                    if (raw instanceof byte[] binary) {
                        values.put(name, bounded("binary:sha256:" + digest(binary) + ":bytes:" + binary.length));
                        binaries.add(name);
                    } else values.put(name, bounded(raw.toString()));
                }
                result.add(new Row(table, Map.copyOf(values), Set.copyOf(binaries)));
            }
        }
        return List.copyOf(result);
    }

    private String bounded(String value) {
        if (value == null) return "";
        if (value.length() > MAX_CELL || (textCharacters += value.length()) > MAX_TEXT)
            throw fail("QEA_LIMIT", "A QEA cell or the total retained text exceeds the supported bounds");
        return value;
    }
    private void budget() {
        if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) throw fail("QEA_LIMIT", "The QEA read deadline was exceeded");
    }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible); }
    }
}
