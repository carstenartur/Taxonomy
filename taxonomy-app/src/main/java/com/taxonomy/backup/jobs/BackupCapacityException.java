package com.taxonomy.backup.jobs;

public final class BackupCapacityException extends IllegalStateException {
    public BackupCapacityException() { super("Backup capacity is currently unavailable"); }
}
