package com.taxonomy.interop.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationFileBinding;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static com.taxonomy.exchange.backup.PortableRows.*;

/** Binary evidence is streamed as archive entries; only typed references enter portable JSON records. */
final class IntegrationBackupFiles {
    private static final String COLUMNS = "e.id,e.scope_id,e.connection_id,e.operation_id,e.actor,e.kind,e.profile,e.profile_version,e.binding_json,"
            + "e.sha256,e.byte_count,e.media_type,e.filename,e.model_identifier,e.review_fingerprint,e.content,e.created_at,"
            + "o.actor as source_actor,o.context_json,o.document_json,o.direction,o.base_file_id,o.result_file_id,o.review_fingerprint as source_review";
    private final PortableRows rows;
    private final IntegrationBackupSelection selection;
    private final ComponentSink sink;
    private final BackupProfile profile;
    private final List<Query> queries;

    IntegrationBackupFiles(PortableRows rows, IntegrationBackupSelection selection, ComponentSink sink, BackupProfile profile) {
        this.rows = rows; this.selection = selection; this.sink = sink; this.profile = profile;
        String retained = profile.includesHistory() ? "1=1" : "e.kind='BASE' and exists (select 1 from interop_operation p "
                + "where p.base_file_id=e.id and p.scope_id=e.scope_id and p.connection_id=e.connection_id and p.status not in ('COMPLETED','CANCELLED'))";
        queries = selection.queries(COLUMNS, "interop_file e join interop_operation o on o.id=e.operation_id", retained);
    }
    void verify() throws IOException {
        for (var query : queries) rows.visit(query, row -> {
            sink.checkpoint(); var metadata = metadata(row); verifyContent(row, metadata); return metadata;
        }, ignored -> { });
    }
    void write() throws IOException {
        rows.writeBatches(sink, "interop", "files", profile, queries, this::metadata);
        for (var query : queries) rows.visit(query, row -> {
            sink.checkpoint(); var metadata = metadata(row);
            try (InputStream content = row.getBinaryStream("content")) {
                if (content == null) throw invalid();
                BackupEntry written = sink.write(metadata.path(), new BoundedInput(content, metadata.bytes()));
                if (written.length() != metadata.bytes() || !written.sha256().equals(metadata.sha256())) throw invalid();
            }
            return metadata;
        }, ignored -> { });
    }
    private FileData metadata(ResultSet row) throws SQLException, IOException {
        try {
            String id = text(row, "id"), kind = text(row, "kind"), actor = text(row, "actor");
            UUID.fromString(id); UUID.fromString(text(row, "connection_id")); UUID.fromString(text(row, "operation_id"));
            String sha = text(row, "sha256"); long bytes = row.getLong("byte_count");
            var context = IntegrationBackupJson.read(text(row, "context_json"), IntegrationContext.class);
            var document = IntegrationBackupJson.read(text(row, "document_json"), ExchangeDocument.class);
            var binding = IntegrationFileBinding.plugin(document);
            if (!actor.equals(text(row, "source_actor")) || !actor.equals(context.actor())
                    || !text(row, "profile").equals(context.profile()) || !text(row, "profile_version").equals(context.profileVersion())
                    || !text(row, "connection_id").equals(context.connectionId().toString())
                    || !binding.equals(IntegrationBackupJson.read(text(row, "binding_json"), Map.class))
                    || sha == null || !sha.matches("[0-9a-f]{64}") || bytes < 1 || bytes > ExchangeXml.MAX_BYTES) throw invalid();
            if ("BASE".equals(kind)) {
                if (!id.equals(text(row, "base_file_id"))) throw invalid();
            } else if ("RESULT".equals(kind)) {
                if (!id.equals(text(row, "result_file_id")) || text(row, "source_review") == null
                        || !Objects.equals(text(row, "review_fingerprint"), text(row, "source_review"))) throw invalid();
            } else throw invalid();
            return new FileData(new SourceRecordId("interop.file", id), selection.scope(row),
                    reference(row, "interop.connection", "connection_id"), reference(row, "interop.operation", "operation_id"), actor, kind,
                    context.profile(), context.profileVersion(), binding, sha, bytes, text(row, "media_type"), text(row, "filename"),
                    text(row, "model_identifier"), text(row, "review_fingerprint"), "data/interop/files/" + id + ".bin", "MANUAL_REVIEW_REQUIRED");
        } catch (IllegalArgumentException | NullPointerException failure) { throw invalid(); }
    }
    private void verifyContent(ResultSet row, FileData metadata) throws SQLException, IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        long bytes = 0;
        try (InputStream content = row.getBinaryStream("content")) {
            if (content == null) throw invalid();
            byte[] buffer = new byte[8192];
            for (int count; (count = content.read(buffer)) != -1;) {
                sink.checkpoint(); bytes += count;
                if (bytes > metadata.bytes()) throw invalid();
                digest.update(buffer, 0, count);
            }
        }
        if (bytes != metadata.bytes() || !HexFormat.of().formatHex(digest.digest()).equals(metadata.sha256())) throw invalid();
    }
    private static IOException invalid() { return new IOException("Invalid integration binary evidence"); }
    private static final class BoundedInput extends FilterInputStream {
        private final long limit; private long read;
        BoundedInput(InputStream input, long limit) { super(input); this.limit = limit; }
        @Override public int read() throws IOException { int value = in.read(); if (value >= 0) count(1); return value; }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int value = in.read(buffer, offset, length); if (value > 0) count(value); return value;
        }
        private void count(int count) throws IOException { read += count; if (read > limit || Thread.currentThread().isInterrupted()) throw invalid(); }
    }
    record FileData(SourceRecordId sourceId, BackupIntegrationScope scope, SourceRecordId connection, SourceRecordId sourceOperation,
                    String sourceActor, String kind, String profile, String profileVersion, Map<String, String> sourcePluginBinding,
                    String sha256, long bytes, String mediaType, String filename, String modelIdentifier, String sourceReviewFingerprint,
                    String path, String targetExecutionPolicy) { }
}
