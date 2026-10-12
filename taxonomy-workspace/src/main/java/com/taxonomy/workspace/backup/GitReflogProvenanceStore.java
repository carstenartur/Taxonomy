package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static com.taxonomy.workspace.backup.GitTreeCapture.failure;
import static com.taxonomy.workspace.backup.GitTreeCapture.sanitized;

/** Workspace-owned immutable evidence, enlisted in the caller's verified target transaction. */
public final class GitReflogProvenanceStore {
    static final int MAX_EVENT_BYTES = 512 * 1_024;
    private final DataSource database;
    public GitReflogProvenanceStore(DataSource database) { this.database = Objects.requireNonNull(database); }
    /** Completed-owner readback must detect missing evidence, never reinsert it on a retry. */
    public void requireStored(Connection target, BackupRepositoryKey key, String storage,
            List<GitReflogProvenance.Event> evidence, BackupCheckpoint checkpoint) throws IOException {
        try {
            checkpoint.check();
            if(target.getAutoCommit())throw failure("Original Git evidence requires a verified readback transaction");
            var expected=GitReflogProvenance.canonical(evidence,BackupLimits.MAX_ITEMS,checkpoint);
            if(!expected.equals(read(target,key,storage,BackupLimits.MAX_ITEMS,checkpoint)))
                throw failure("Completed original Git event evidence is missing or changed");
            checkpoint.check();
        } catch(IOException|SQLException|RuntimeException problem){throw sanitized(problem);}
    }
    public List<GitReflogProvenance.Event> read(BackupRepositoryKey key, String storage, int maximum,
                                               BackupCheckpoint checkpoint) throws IOException {
        try (var connection = database.getConnection()) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (Rollback rollback=connection::rollback) { return read(connection, key, storage, maximum, checkpoint); }
        } catch (IOException | SQLException | RuntimeException problem) { throw sanitized(problem); }
    }
    /** Does not commit: target data, provenance, owner proof and retry ledger share one transaction. */
    public void retain(Connection target, BackupRepositoryKey key, String storage,
                       List<GitReflogProvenance.Event> evidence, BackupCheckpoint checkpoint) throws IOException {
        try {
            requireRoute(key, storage); checkpoint.check();
            if (target.getAutoCommit() || target.isReadOnly()) throw failure("Original Git events require the verified target transaction");
            var expected = GitReflogProvenance.canonical(evidence, BackupLimits.MAX_ITEMS, checkpoint);
            var actual = read(target, key, storage, BackupLimits.MAX_ITEMS, checkpoint);
            if (!actual.isEmpty()) {
                if (!actual.equals(expected)) throw failure("Original Git event evidence cannot be replaced");
                return;
            }
            String repository = GitReflogProvenance.repositoryKey(key);
            String sql = "insert into workspace_git_reflog_provenance(id,repository_key,repository_id,workspace_id,storage_repository_name,origin_id,event_ordinal,body_version,sha256,body) values(?,?,?,?,?,?,?,?,?,?)";
            try (var statement = target.prepareStatement(sql)) {
                statement.setQueryTimeout(60);
                for (var event : expected) {
                    checkpoint.check(); String origin = GitReflogProvenance.originId(event.origin()); byte[] body = GitReflogProvenance.encoded(event);
                    statement.setString(1, GitReflogProvenance.eventId(repository, origin, event.ordinal()));
                    statement.setString(2, repository); statement.setString(3, key.repositoryId()); statement.setString(4, key.workspaceId());
                    statement.setString(5, storage); statement.setString(6, origin); statement.setInt(7, event.ordinal()); statement.setInt(8, 1);
                    statement.setString(9, GitReflogProvenance.digest(body)); statement.setCharacterStream(10, new StringReader(new String(body, StandardCharsets.UTF_8)));
                    if (statement.executeUpdate() != 1) throw failure("Original Git event persistence was not confirmed");
                    checkpoint.check();
                }
            }
            if (!expected.equals(read(target, key, storage, BackupLimits.MAX_ITEMS, checkpoint)))
                throw failure("Original Git event persistence differs from its proof");
            checkpoint.check();
        } catch (IOException | SQLException | RuntimeException problem) { throw sanitized(problem); }
    }
    private static List<GitReflogProvenance.Event> read(Connection connection, BackupRepositoryKey key, String storage,
            int maximum, BackupCheckpoint checkpoint) throws SQLException, IOException {
        requireRoute(key, storage);
        if (maximum < 0 || maximum > BackupLimits.MAX_ITEMS) throw failure("Invalid original Git event limit");
        var events = new ArrayList<GitReflogProvenance.Event>(); long total = 0;
        String repository = GitReflogProvenance.repositoryKey(key);
        String sql = "select id,repository_key,repository_id,workspace_id,storage_repository_name,origin_id,event_ordinal,body_version,sha256,body from workspace_git_reflog_provenance where repository_key=? order by origin_id,event_ordinal";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(60); statement.setMaxRows(maximum + 1); statement.setFetchSize(Math.min(200, maximum + 1));
            statement.setString(1, repository); checkpoint.check();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    checkpoint.check();
                    if (events.size() >= maximum || !repository.equals(rows.getString(2)) || !key.repositoryId().equals(rows.getString(3))
                            || !Objects.equals(key.workspaceId(), rows.getString(4)) || !storage.equals(rows.getString(5)) || rows.getInt(8) != 1)
                        throw failure("Original Git event scope, schema or bound differs");
                    byte[] body = body(rows.getCharacterStream(10), checkpoint);
                    if (body.length > GitHistoryReflogs.MAX_TOTAL_BYTES - total || !GitReflogProvenance.digest(body).equals(rows.getString(9)))
                        throw failure("Original Git event content differs or exceeds its bound");
                    total += body.length;
                    var event = PortableRows.json().readValue(body, GitReflogProvenance.Event.class);
                    String origin = GitReflogProvenance.originId(event.origin());
                    if (!origin.equals(rows.getString(6)) || event.ordinal() != rows.getInt(7)
                            || !GitReflogProvenance.eventId(repository, origin, event.ordinal()).equals(rows.getString(1)))
                        throw failure("Original Git event position or origin differs");
                    events.add(event);
                }
            }
        }
        return GitReflogProvenance.canonical(events, maximum, checkpoint);
    }
    private static byte[] body(Reader source, BackupCheckpoint checkpoint) throws IOException {
        if (source == null) throw failure("Original Git event content is missing");
        try (source) {
            var output = new StringBuilder(); var buffer = new char[4_096];
            for (;;) {
                checkpoint.check(); int count = source.read(buffer); if (count < 0) break;
                if (count == 0) { int one = source.read(); if (one < 0) break; buffer[0] = (char) one; count = 1; }
                if (count > MAX_EVENT_BYTES - output.length()) throw failure("Original Git event content exceeds its bound");
                output.append(buffer, 0, count);
            }
            var encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(output));
            if (encoded.remaining() > MAX_EVENT_BYTES) throw failure("Original Git event content exceeds its bound");
            var bytes = new byte[encoded.remaining()]; encoded.get(bytes); return bytes;
        }
    }
    private static void requireRoute(BackupRepositoryKey key, String storage) throws IOException {
        Objects.requireNonNull(key);
        if (storage == null || storage.isBlank() || !storage.equals(storage.strip()) || storage.length() > 512 || storage.indexOf('\0') >= 0)
            throw failure("Invalid original Git event target routing");
    }
    @FunctionalInterface private interface Rollback extends AutoCloseable {
        @Override void close()throws SQLException;
    }
}
