package com.taxonomy.catalog.backup;

import com.taxonomy.backup.*;
import com.taxonomy.catalog.provenance.CatalogueSourceBytes;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal.Use;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PortableRows.Query;
import javax.sql.DataSource;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.*;

/**
 * Retained base-catalogue input evidence. The caller must hold a stable, fenced capture lease.
 * Raw inputs may contain superseded content, so only history profiles can include them.
 */
public final class CatalogueSourceBackupContributor implements BackupDataContributor {
    private static final int MAX_IDENTITIES = 100_000;
    private static final String REVISION_KIND = "knowledge.catalogue-source-revision";
    private static final String BLOB_KIND = "knowledge.catalogue-source-blob";
    private static final String REVISION_SELECT = "select r.id,r.created_at,r.workbook_sha256,r.workbook_use,"
            + "r.overlay_sha256,r.overlay_use,r.relations_sha256,r.relations_use,"
            + "w.byte_length as workbook_length,o.byte_length as overlay_length,c.byte_length as relations_length "
            + "from catalogue_source_revision r left join catalogue_source_blob w on w.sha256=r.workbook_sha256 "
            + "left join catalogue_source_blob o on o.sha256=r.overlay_sha256 "
            + "left join catalogue_source_blob c on c.sha256=r.relations_sha256 ";
    private final DataSource database;
    private final PortableRows rows;
    private final SelectionReader selection;

    /**
     * Server proof must authorize each WHOLE input revision, including all original-file contents.
     * Merely referencing one node from a global workbook does not establish that authority.
     * Never build this selection by enumerating global sources for an ordinary scoped request.
     */
    @FunctionalInterface public interface SelectionReader { Set<String> select(SnapshotContext context) throws IOException; }
    public CatalogueSourceBackupContributor(DataSource database, SelectionReader selection) {
        this.database = Objects.requireNonNull(database); rows = new PortableRows(database); this.selection = Objects.requireNonNull(selection);
    }
    @Override public BackupComponentId componentId() { return new BackupComponentId("knowledge"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() { return Set.of("com.taxonomy.catalog.provenance.CatalogueSourceBlob",
            "com.taxonomy.catalog.provenance.CatalogueSourceRevision", "com.taxonomy.catalog.provenance.CatalogueSourceState", "storage.files.catalogue"); }
    @Override public List<String> omissions(BackupProfile profile) {
        var result = new ArrayList<String>();
        result.add("knowledge: originals predating retention remain NOT_RETAINED; configured resources never stand in for missing originals");
        if (profile == BackupProfile.SELECTED_VERSION) result.add("knowledge: unversioned live catalogue source evidence is outside selected-version scope");
        else if (!profile.includesHistory()) result.add("knowledge: raw catalogue inputs and prior source revisions require history; current materialized nodes are exported separately");
        if (!profile.isInstallation()) result.add("knowledge: whole catalogue inputs require explicit server authorization; node references alone do not authorize global original files");
        return List.copyOf(result);
    }

    @Override public void write(SnapshotContext context, ComponentSink sink) throws IOException {
        try { capture(context, sink); }
        catch (InterruptedIOException failure) { Thread.currentThread().interrupt(); throw cancelled(); }
        catch (IOException | RuntimeException failure) {
            // JDBC cleanup can attach private driver diagnostics to an otherwise safe IOException.
            interrupted(); throw invalid();
        }
    }

    private void capture(SnapshotContext context, ComponentSink sink) throws IOException {
        sink.checkpoint();
        var profile = context.authorization().request().profile();
        Set<String> requested = profile == BackupProfile.SELECTED_VERSION || profile.isInstallation() ? Set.of() : selected(context);
        if (profile == BackupProfile.SELECTED_VERSION || (!profile.isInstallation() && requested.isEmpty())) {
            emit(profile, sink, null, List.of(), Map.of(), Map.of()); return;
        }
        var states = new ArrayList<StateRecord>();
        rows.visit(new Query("select id,current_revision from catalogue_source_state", List.of()), this::state, state -> {
            sink.checkpoint(); if (!states.isEmpty()) throw invalid(); states.add(state);
        });
        String current = states.isEmpty() || states.getFirst().currentRevision() == null ? null : states.getFirst().currentRevision().value();
        if (current == null) {
            rows.requireEmpty(new Query("select 1 from catalogue_source_revision", List.of()));
            rows.requireEmpty(new Query("select 1 from catalogue_source_blob", List.of()));
            if (!requested.isEmpty()) throw invalid();
            emit(profile, sink, null, List.of(), Map.of(), Map.of()); return;
        }
        rows.requireEmpty(new Query("select 1 from catalogue_source_state s left join catalogue_source_revision r on r.id=s.current_revision where r.id is null", List.of()));
        if (!profile.isInstallation() && !profile.includesHistory() && !requested.equals(Set.of(current))) throw invalid();

        List<Query> queries = profile.isInstallation() && profile.includesHistory()
                ? List.of(new Query(REVISION_SELECT + "order by r.id", List.of()))
                : batches(REVISION_SELECT + "where r.id in ", profile.isInstallation() ? Set.of(current) : requested, "r.id");
        var revisions = new TreeMap<String, RevisionRecord>(); var blobs = new TreeMap<String, BlobRecord>();
        for (var query : queries) rows.visit(query, r -> revision(r, profile.includesHistory()), revision -> {
            sink.checkpoint();
            if (revisions.size() >= MAX_IDENTITIES || revisions.putIfAbsent(revision.sourceId().value(), revision) != null) throw invalid();
            if (profile.includesHistory()) for (var input : List.of(revision.workbook(), revision.overlay(), revision.relations())) {
                if (input.blob() == null) continue;
                var blob = new BlobRecord(input.blob(), input.sha256(), input.length(), path(input.sha256()));
                var existing = blobs.putIfAbsent(input.sha256(), blob);
                if (blobs.size() > MAX_IDENTITIES || (existing != null && !existing.equals(blob))) throw invalid();
            }
        });
        if (profile.isInstallation() ? !revisions.containsKey(current) : !revisions.keySet().equals(requested)) throw invalid();
        if (profile.isInstallation() && profile.includesHistory()) rows.requireEmpty(new Query(
                "select 1 from catalogue_source_blob b where not exists (select 1 from catalogue_source_revision r "
                        + "where r.workbook_sha256=b.sha256 or r.overlay_sha256=b.sha256 or r.relations_sha256=b.sha256)", List.of()));
        // Validate all selected raw bytes before the first archive entry, then verify again while streaming.
        for (var blob : blobs.values()) transfer(blob, sink, false);
        StateRecord state = revisions.containsKey(current) ? states.getFirst() : null;
        emit(profile, sink, state, queries, revisions, blobs);
    }

    private Set<String> selected(SnapshotContext context) throws IOException {
        Set<String> candidates = selection.select(context);
        if (candidates == null || candidates.size() > MAX_IDENTITIES) throw invalid();
        var result = new TreeSet<String>();
        for (String candidate : candidates) result.add(uuid(candidate));
        return result;
    }

    private void emit(BackupProfile profile, ComponentSink sink, StateRecord state, List<Query> queries,
                      Map<String, RevisionRecord> revisions, Map<String, BlobRecord> blobs) throws IOException {
        int[] stateCount = { 0 };
        sink.checkpoint(); rows.write(sink, "knowledge", "catalogue-source-state", profile,
                state == null ? null : new Query("select id,current_revision from catalogue_source_state", List.of()), r -> {
                    sink.checkpoint(); var actual = state(r);
                    if (++stateCount[0] != 1 || !actual.equals(state)) throw invalid();
                    return actual;
                });
        if (stateCount[0] != (state == null ? 0 : 1)) throw invalid();
        var remainingRevisions = new HashMap<>(revisions);
        sink.checkpoint(); rows.writeBatches(sink, "knowledge", "catalogue-source-revision", profile, queries, r -> {
            sink.checkpoint(); var actual = revision(r, profile.includesHistory());
            if (!actual.equals(remainingRevisions.remove(actual.sourceId().value()))) throw invalid();
            return actual;
        });
        if (!remainingRevisions.isEmpty()) throw invalid();
        var remainingBlobs = new HashMap<>(blobs);
        sink.checkpoint(); rows.writeBatches(sink, "knowledge", "catalogue-source-blob", profile,
                batches("select sha256,byte_length from catalogue_source_blob where sha256 in ", blobs.keySet(), "sha256"), r -> {
                    sink.checkpoint(); var expected = remainingBlobs.remove(r.getString("sha256")); Long length = PortableRows.number(r, "byte_length");
                    if (expected == null || length == null || length != expected.length()) throw invalid();
                    return expected;
                });
        if (!remainingBlobs.isEmpty()) throw invalid();
        for (var blob : blobs.values()) transfer(blob, sink, true);
    }

    private StateRecord state(ResultSet row) throws SQLException, IOException {
        if (!"base-catalogue".equals(row.getString("id"))) throw invalid();
        String current = row.getString("current_revision");
        return new StateRecord(new SourceRecordId("knowledge.catalogue-source-state", "base-catalogue"),
                current == null ? null : new SourceRecordId(REVISION_KIND, uuid(current)));
    }
    private RevisionRecord revision(ResultSet row, boolean history) throws SQLException, IOException {
        Instant created;
        try { created = Instant.parse(row.getString("created_at")); }
        catch (DateTimeException | NullPointerException failure) { throw invalid(); }
        return new RevisionRecord(new SourceRecordId(REVISION_KIND, uuid(row.getString("id"))), created,
                input(row, "workbook", history), input(row, "overlay", history), input(row, "relations", history));
    }
    private InputRecord input(ResultSet row, String role, boolean history) throws SQLException, IOException {
        Use use;
        try { use = Use.valueOf(row.getString(role + "_use")); }
        catch (IllegalArgumentException | NullPointerException failure) { throw invalid(); }
        String hash = row.getString(role + "_sha256"); Long length = PortableRows.number(row, role + "_length");
        if (use == Use.NOT_USED || use == Use.NOT_RETAINED) {
            if (hash != null || length != null) throw invalid();
            return new InputRecord(use, null, 0, use == Use.NOT_USED ? Payload.NOT_USED : Payload.UNAVAILABLE, null);
        }
        if (hash == null || !hash.matches("[0-9a-f]{64}") || length == null || length < 0 || length > CatalogueSourceBytes.MAX_BYTES) throw invalid();
        return new InputRecord(use, hash, length, history ? Payload.INCLUDED : Payload.HISTORY_REQUIRED,
                history ? new SourceRecordId(BLOB_KIND, hash) : null);
    }
    private static String uuid(String value) throws IOException {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid(); return value; }
        catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static List<Query> batches(String select, Set<String> ids, String order) {
        var sorted = ids.stream().sorted().toList(); var result = new ArrayList<Query>();
        for (int start = 0; start < sorted.size(); start += 200) {
            var part = sorted.subList(start, Math.min(start + 200, sorted.size()));
            result.add(new Query(select + "(" + String.join(",", Collections.nCopies(part.size(), "?")) + ") order by " + order, part));
        }
        return result;
    }

    private void transfer(BlobRecord blob, ComponentSink sink, boolean write) throws IOException {
        sink.checkpoint();
        try (var connection = database.getConnection()) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select byte_length,payload from catalogue_source_blob where sha256=?")) {
                statement.setQueryTimeout(60); statement.setFetchSize(1); statement.setString(1, blob.sha256());
                try (var result = statement.executeQuery()) {
                    if (!result.next() || result.getLong("byte_length") != blob.length()) throw invalid();
                    InputStream source = result.getBinaryStream("payload"); if (source == null) throw invalid();
                    try (var input = new VerifiedInput(source, blob, sink)) {
                        if (write) {
                            var receipt = sink.write(blob.path(), input); input.finish();
                            if (!new BackupEntry(blob.path(), blob.length(), blob.sha256()).equals(receipt)) throw invalid();
                        } else { input.transferTo(OutputStream.nullOutputStream()); input.finish(); }
                    }
                    if (result.next()) throw invalid();
                }
            } finally { connection.rollback(); }
        } catch (SQLException | RuntimeException failure) {
            interrupted(); throw invalid();
        }
    }
    private static final class VerifiedInput extends InputStream {
        private final InputStream source;
        private final BlobRecord blob;
        private final ComponentSink sink;
        private final MessageDigest digest;
        private long count;
        private boolean verified;
        VerifiedInput(InputStream source, BlobRecord blob, ComponentSink sink) {
            this.source = source; this.blob = blob; this.sink = sink;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable"); }
        }
        @Override public int read() throws IOException { var single = new byte[1]; return read(single, 0, 1) < 0 ? -1 : single[0] & 255; }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) return 0;
            sink.checkpoint(); if (verified) return -1;
            int read;
            try {
                read = source.read(buffer, offset, Math.min(length, 8192));
                if (read == 0) {
                    int value = source.read();
                    if (value < 0) read = -1;
                    else { buffer[offset] = (byte) value; read = 1; }
                }
            } catch (InterruptedIOException failure) { Thread.currentThread().interrupt(); throw cancelled(); }
            catch (IOException | RuntimeException failure) { interrupted(); throw invalid(); }
            sink.checkpoint();
            if (read < 0) {
                if (count != blob.length() || !HexFormat.of().formatHex(digest.digest()).equals(blob.sha256())) throw invalid();
                verified = true; return -1;
            }
            if (read > blob.length() - count) throw invalid();
            digest.update(buffer, offset, read); count += read; return read;
        }
        void finish() throws IOException { if (read() != -1 || !verified) throw invalid(); }
        @Override public void close() throws IOException {
            try { source.close(); }
            catch (InterruptedIOException failure) { Thread.currentThread().interrupt(); throw cancelled(); }
            catch (IOException | RuntimeException failure) { interrupted(); throw invalid(); }
            interrupted();
        }
    }
    private static void interrupted() throws InterruptedIOException { if (Thread.currentThread().isInterrupted()) throw cancelled(); }
    private static InterruptedIOException cancelled() { return new InterruptedIOException("Catalogue source capture interrupted"); }
    private static IOException invalid() { return new IOException("Retained catalogue source capture is incomplete or inconsistent"); }
    private static String path(String sha256) { return "files/catalogue/" + sha256 + ".bin"; }
    public enum Payload { INCLUDED, HISTORY_REQUIRED, UNAVAILABLE, NOT_USED }
    public record InputRecord(Use use, String sha256, long length, Payload payload, SourceRecordId blob) { }
    public record StateRecord(SourceRecordId sourceId, SourceRecordId currentRevision) { }
    public record RevisionRecord(SourceRecordId sourceId, Instant createdAt, InputRecord workbook, InputRecord overlay, InputRecord relations) { }
    public record BlobRecord(SourceRecordId sourceId, String sha256, long length, String path) { }
}
