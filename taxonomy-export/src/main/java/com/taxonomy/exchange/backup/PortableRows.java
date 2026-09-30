package com.taxonomy.exchange.backup;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taxonomy.backup.*;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Bounded, pull-based NDJSON on the capture thread. Only explicitly constructed DTO records enter JSON. */
public final class PortableRows {
    public static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;
    private static final long MAX_ROWS = 1_000_000;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static { JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(100).maxStringLength(MAX_RECORD_BYTES).maxNumberLength(100).build()); }
    private final DataSource database;

    public PortableRows(DataSource database) { this.database = Objects.requireNonNull(database); }
    @FunctionalInterface public interface Mapper<T extends Record> { T read(ResultSet row) throws SQLException, IOException; }
    public record Header(int schemaVersion, String kind, BackupProfile profile) { }
    public record Query(String sql, List<?> parameters) {
        public Query { Objects.requireNonNull(sql); parameters = List.copyOf(parameters); }
    }

    /** Reject a broken/unauthorized dependency rather than silently dropping its source record. */
    public void requireEmpty(Query query) throws IOException {
        try (var connection = database.getConnection()) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(query.sql())) {
                statement.setMaxRows(1); statement.setQueryTimeout(60);
                for (int i=0;i<query.parameters().size();i++) statement.setObject(i+1,query.parameters().get(i));
                try (var result = statement.executeQuery()) {
                    if (result.next()) throw new IOException("Portable selection has a missing or cross-tenant dependency");
                }
            } finally { connection.rollback(); }
        } catch (SQLException failure) { throw new IOException("Portable dependency verification failed"); }
    }

    public <T extends Record> void write(ComponentSink sink, String component, String kind, BackupProfile profile,
                                        Query query, Mapper<T> mapper) throws IOException {
        if (!component.matches("[a-z][a-z0-9-]{0,63}") || !kind.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid dataset name");
        String path = "data/" + component + "/" + kind + ".ndjson";
        if (query == null) {
            try (var input = new ByteArrayInputStream(line(new Header(1, kind, profile)))) { sink.write(path, input); }
            return;
        }
        try (var connection = database.getConnection()) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(query.sql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                statement.setFetchSize(64); statement.setQueryTimeout(60);
                for (int i = 0; i < query.parameters().size(); i++) statement.setObject(i + 1, query.parameters().get(i));
                try (var rows = statement.executeQuery(); var input = new RecordInput<>(new Header(1, kind, profile), rows, mapper)) {
                    sink.write(path, input);
                }
            } finally { connection.rollback(); }
        } catch (SQLException failure) {
            // SQL, driver text and possibly bound source data are not public failure details.
            throw new IOException("Portable dataset capture failed");
        }
    }

    public static void document(ComponentSink sink, String path, Record document) throws IOException {
        try (var input = new ByteArrayInputStream(line(document))) { sink.write(path, input); }
    }
    public static JsonMapper json() { return JSON.copy(); }
    public static SourceRecordId reference(ResultSet row, String kind, String column) throws SQLException, IOException {
        // JDBC does not require numeric IDs to support getCharacterStream (HSQL deliberately rejects it).
        // Identifier columns are bounded scalars, unlike the text/LOB columns handled by text().
        String value = row.getString(column);
        if (value != null && value.length() > 1024) throw new IOException("Portable reference limit exceeded");
        return value == null ? null : new SourceRecordId(kind, value);
    }
    /** Read LOB text through a bounded character stream; never allocate an unbounded driver String. */
    public static String text(ResultSet row, String column) throws SQLException, IOException {
        try (Reader input = row.getCharacterStream(column)) {
            if (input == null) return null;
            var value = new StringBuilder(); var buffer = new char[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                cancelled();
                if (count > MAX_RECORD_BYTES / 2 - value.length()) throw new IOException("Portable text limit exceeded");
                value.append(buffer, 0, count);
            }
            return value.toString();
        }
    }
    public static String instant(ResultSet row, String column) throws SQLException {
        int index = row.findColumn(column);
        int type = row.getMetaData().getColumnType(index);
        if (type == Types.TIMESTAMP_WITH_TIMEZONE || type == -101 || type == -155) {
            // JDBC 4.2 retains the stored offset. Calendar conversion on HSQL's zoned timestamps
            // changes the instant by the process offset, so it must not be used for this type.
            var value = row.getObject(column, OffsetDateTime.class);
            return value == null ? null : value.toInstant().toString();
        }
        var timestamp = row.getTimestamp(column, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        return timestamp == null ? null : timestamp.toInstant().toString();
    }
    public static Long number(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column); return row.wasNull() ? null : value;
    }
    public static String unframe(String source) throws IOException {
        if (source == null || !source.startsWith("1:")) throw new IOException("Unsupported journal body version");
        return source.substring(2);
    }
    private static byte[] line(Record record) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var bounded = new FilterOutputStream(output) {
            private int count;
            @Override public void write(int value) throws IOException { check(1); out.write(value); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { check(length); out.write(bytes, offset, length); }
            private void check(int length) throws IOException {
                if (length > MAX_RECORD_BYTES - count) throw new IOException("Portable record limit exceeded"); count += length;
            }
        }) { JSON.writeValue(bounded, record); }
        output.write('\n'); return output.toByteArray();
    }
    private static void cancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Portable capture cancelled");
    }
    private static final class RecordInput<T extends Record> extends InputStream {
        private final ResultSet rows;
        private final Mapper<T> mapper;
        private byte[] current;
        private int position;
        private long count;
        RecordInput(Header header, ResultSet rows, Mapper<T> mapper) throws IOException {
            this.current = line(header); this.rows = rows; this.mapper = mapper;
        }
        @Override public int read() throws IOException {
            if (!availableRecord()) return -1; return current[position++] & 255;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) return 0;
            if (!availableRecord()) return -1;
            int copy = Math.min(length, current.length - position);
            System.arraycopy(current, position, buffer, offset, copy); position += copy; return copy;
        }
        private boolean availableRecord() throws IOException {
            cancelled();
            while (position == current.length) {
                try {
                    if (!rows.next()) return false;
                    if (++count > MAX_ROWS) throw new IOException("Portable row limit exceeded");
                    T record = mapper.read(rows);
                    if (record == null) continue;
                    current = line(record); position = 0;
                } catch (SQLException failure) { throw new IOException("Portable row capture failed"); }
            }
            return true;
        }
    }
}
