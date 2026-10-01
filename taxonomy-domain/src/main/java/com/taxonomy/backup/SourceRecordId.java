package com.taxonomy.backup;

/** An archive-local source reference, never a primary key to insert into the target database. */
public record SourceRecordId(String kind, String value) {
    public SourceRecordId {
        if (kind == null || !kind.matches("[a-z][a-z0-9.-]{0,95}")) throw new IllegalArgumentException("Invalid record kind");
        BackupChecks.text(value, "source record reference");
    }
}
