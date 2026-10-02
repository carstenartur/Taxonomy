package com.taxonomy.templates.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.templates.DocumentTemplateGitRepository;

import java.io.IOException;
import java.util.*;

/** Administrative, installation-owned templates must never leak into a workspace export. */
public final class TemplateBackupContributor implements BackupDataContributor {
    private static final String CURRENT_ROOT = "data/templates/current/";
    private final DocumentTemplateGitRepository repository;

    public TemplateBackupContributor(DocumentTemplateGitRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override public BackupComponentId componentId() { return new BackupComponentId("templates"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() { return Set.of("storage.git.document-templates"); }
    @Override public List<String> omissions(BackupProfile profile) {
        if (!profile.isInstallation()) return List.of("templates: installation-owned administrative templates are outside the selected workspace scope");
        if (profile == BackupProfile.INSTALLATION_CURRENT) return List.of("templates: earlier revisions and repository history are excluded from current state");
        return List.of();
    }

    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        var profile = snapshot.authorization().request().profile();
        if (profile == BackupProfile.INSTALLATION_FULL) throw new IOException("Template repository history capture is required for INSTALLATION_FULL");
        if (!profile.isInstallation()) {
            PortableRows.document(sink, "data/templates/inventory.json",
                    new Inventory(1, profile, Selection.OUTSIDE_SCOPE, null, List.of()));
            return;
        }
        var captured = repository.captureCurrentTree(sink::checkpoint);
        // Check complete destination paths before emitting the first payload, including added prefixes.
        for (var file : captured.files()) {
            sink.checkpoint();
            PortableGitPaths.requireFile(CURRENT_ROOT + file.path());
        }
        var files = new ArrayList<FileRecord>();
        for (var file : captured.files()) {
            sink.checkpoint();
            try (var input = repository.openCapturedFile(file)) {
                var entry = sink.write(CURRENT_ROOT + file.path(), input);
                if (entry.length() != file.length()) throw new IOException("Template capture size mismatch");
                files.add(new FileRecord(file.path(), file.objectId(), entry));
            }
        }
        PortableRows.document(sink, "data/templates/inventory.json",
                new Inventory(1, profile, Selection.CURRENT_TREE, captured.commitId(), List.copyOf(files)));
    }

    public enum Selection { CURRENT_TREE, OUTSIDE_SCOPE }
    public record FileRecord(String sourcePath, String sourceObjectId, BackupEntry entry) { }
    public record Inventory(int schemaVersion, BackupProfile profile, Selection selection, String sourceCommit,
                            List<FileRecord> files) { }
}
