package com.taxonomy.architecture.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PortableRows.Query;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static com.taxonomy.exchange.backup.PortableRows.*;

/** Legacy parsed documents have no repository columns; only captured content can prove a scoped payload. */
public final class ArchitectureBackupContributor implements BackupDataContributor {
    private final PortableRows rows;
    private final BackupDocumentProjector projector;
    private final BackupDocumentReference.Selector references;

    public ArchitectureBackupContributor(DataSource database, BackupDocumentProjector projector,
                                         BackupDocumentReference.Selector references) {
        rows = new PortableRows(database);
        this.projector = Objects.requireNonNull(projector);
        this.references = Objects.requireNonNull(references);
    }

    @Override public BackupComponentId componentId() { return new BackupComponentId("architecture"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() { return Set.of("com.taxonomy.architecture.model.ArchitectureDslDocument"); }
    @Override public List<String> omissions(BackupProfile profile) {
        if (profile == BackupProfile.INSTALLATION_FULL) return List.of();
        return List.of("architecture: unattributed legacy documents and unproven global branch, namespace and parse-event metadata require a full installation backup",
                "architecture: scoped documents require captured commit, path and content proof; saved working copies are owned by the workspace component");
    }

    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        var profile = snapshot.authorization().request().profile();
        if (profile == BackupProfile.INSTALLATION_FULL) {
            rows.write(sink, "architecture", "documents", profile, null, ignored -> null);
            rows.write(sink, "architecture", "legacy-documents", profile,
                    new Query("select id,path,commit_id,branch,namespace,dsl_version,raw_content,parsed_at from architecture_dsl_document order by id", List.of()), r -> {
                        sink.checkpoint();
                        return new LegacyDocument(reference(r, "architecture.legacy-document", "id"),
                                LegacyOwnership.INSTALLATION_ONLY, text(r, "path"), text(r, "commit_id"), text(r, "branch"),
                                text(r, "namespace"), text(r, "dsl_version"), text(r, "raw_content"), instant(r, "parsed_at"));
                    });
            return;
        }
        var selected = List.copyOf(references.select(snapshot, sink::checkpoint));
        if (selected.size() > BackupLimits.MAX_ITEMS) throw new IOException("Architecture document selection limit exceeded");
        var byDocument = new LinkedHashMap<DocumentKey, List<BackupDocumentReference>>();
        var unique = new HashSet<BackupDocumentReference>();
        for (var reference : selected) {
            sink.checkpoint();
            requireSelection(snapshot, reference);
            if (!unique.add(reference)) throw new IOException("Duplicate captured architecture document reference");
            byDocument.computeIfAbsent(new DocumentKey(reference.commit(), reference.path()), ignored -> new ArrayList<>()).add(reference);
        }
        var commits = selected.stream().map(BackupDocumentReference::commit).distinct().sorted().toList();
        var batches = new ArrayList<Query>();
        for (int start = 0; start < commits.size(); start += 200) {
            var parameters = commits.subList(start, Math.min(start + 200, commits.size()));
            batches.add(new Query("select id,path,commit_id,raw_content from architecture_dsl_document where commit_id in ("
                    + String.join(",", Collections.nCopies(parameters.size(), "?")) + ") order by id desc", parameters));
        }
        var emitted = new HashSet<ContentKey>();
        rows.writeBatches(sink, "architecture", "documents", profile, batches, r -> {
            sink.checkpoint();
            var key = new DocumentKey(text(r, "commit_id"), text(r, "path"));
            var candidates = byDocument.get(key);
            if (candidates == null) return null;
            String content = text(r, "raw_content");
            if (content == null) throw new IOException("Stored architecture document content is missing");
            String hash = sha256(content);
            var proven = candidates.stream().filter(candidate -> candidate.sha256().equals(hash)).toList();
            if (proven.isEmpty() || !emitted.add(new ContentKey(key, hash))) return null;
            if (emitted.size() > BackupLimits.MAX_ITEMS) throw new IOException("Architecture document capture limit exceeded");
            return new ProvenDocument(reference(r, "architecture.document", "id"), proven,
                    profile.includesHistory() ? content : projector.currentState(content));
        });
        rows.write(sink, "architecture", "legacy-documents", profile, null, ignored -> null);
    }

    private static void requireSelection(SnapshotContext snapshot, BackupDocumentReference reference) throws IOException {
        var state = snapshot.repositories().get(reference.repository());
        if (state == null) throw new IOException("Architecture document proof is outside captured repository scope");
        var request = snapshot.authorization().request();
        if (request.time() instanceof BackupTime.SelectedVersion selected) {
            if (!reference.commit().equals(selected.commitsByRepository().get(reference.repository())))
                throw new IOException("Architecture document proof differs from selected version");
        } else if (request.profile().includesHistory()) {
            if (!state.refs().containsValue(reference.commit()) && !state.requiredCommits().contains(reference.commit()))
                throw new IOException("Architecture document proof is outside captured commit inventory");
        } else if (state.refs().entrySet().stream().noneMatch(ref -> ref.getKey().startsWith("refs/heads/") && ref.getValue().equals(reference.commit()))) {
            throw new IOException("Architecture document proof is not a current branch head");
        }
    }

    private static String sha256(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private record DocumentKey(String commit, String path) { }
    private record ContentKey(DocumentKey document, String hash) { }
    public record ProvenDocument(SourceRecordId sourceId, List<BackupDocumentReference> capturedDocuments, String rawContent) { }
    public enum LegacyOwnership { INSTALLATION_ONLY }
    public record LegacyDocument(SourceRecordId sourceId, LegacyOwnership ownership, String path, String commit,
                                 String branch, String namespace, String dslVersion, String rawContent, String parsedAt) { }
}
