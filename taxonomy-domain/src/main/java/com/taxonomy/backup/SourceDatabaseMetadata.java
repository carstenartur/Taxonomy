package com.taxonomy.backup;

/** Schema-versioned vendor witness captured under the source fence. */
public record SourceDatabaseMetadata(int schemaVersion, SourceDatabaseFamily family) {
    public static final String REQUIRED_FEATURE = "source-database-family-v1";
    public SourceDatabaseMetadata {
        if (schemaVersion != 1 || family == null) throw new IllegalArgumentException("Unsupported database metadata");
    }
}
