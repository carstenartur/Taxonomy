package com.taxonomy.backup;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Explicit dependency of an authorized business record, never a business-key tenant lookup. */
public record BackupSourceReference(SourceRecordId owner, SourceRecordId artifact,
                                    SourceRecordId version, List<SourceRecordId> fragments) {
    public BackupSourceReference {
        Objects.requireNonNull(owner);
        fragments = List.copyOf(fragments);
        if (fragments.size() > 10_000) throw new IllegalArgumentException("Too many source fragments");
        requireKind(artifact, "application.source-artifact");
        requireKind(version, "application.source-version");
        fragments.forEach(id -> requireKind(Objects.requireNonNull(id), "application.source-fragment"));
    }
    private static void requireKind(SourceRecordId reference, String kind) {
        if (reference != null && !reference.kind().equals(kind)) throw new IllegalArgumentException("Wrong source reference kind");
    }
    @FunctionalInterface public interface Selector {
        List<BackupSourceReference> select(SnapshotContext snapshot) throws IOException;
    }
}
