package com.taxonomy.backup.jobs;

public final class BackupJobCancelledException extends IllegalStateException {
    public BackupJobCancelledException() { super("Backup cancellation requested"); }
}
