package com.taxonomy.backup;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Identity-only analysis ownership supplied by the portfolio owner, without historical payloads. */
public record BackupAnalysisReference(SourceRecordId snapshot, SourceRecordId project, SourceRecordId requirement,
                                      BackupRepositoryKey repository, String analysisSession, boolean retained) {
    public BackupAnalysisReference {
        kind(snapshot, "portfolio.analysis-snapshot");
        kind(project, "portfolio.project");
        kind(requirement, "portfolio.requirement");
        Objects.requireNonNull(repository);
        BackupChecks.text(analysisSession, "analysis session");
    }
    private static void kind(SourceRecordId reference, String expected) {
        if (reference == null || !reference.kind().equals(expected)) throw new IllegalArgumentException("Invalid analysis reference kind");
    }
    @FunctionalInterface public interface Selector {
        List<BackupAnalysisReference> select(SnapshotContext snapshot) throws IOException;
    }
}
