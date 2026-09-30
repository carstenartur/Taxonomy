package com.taxonomy.backup.jobs;

public final class BackupJobFencedException extends IllegalStateException {
    public BackupJobFencedException() { super("Backup worker ownership has expired"); }
}
