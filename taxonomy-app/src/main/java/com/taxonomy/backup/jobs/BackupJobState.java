package com.taxonomy.backup.jobs;

public enum BackupJobState {
    QUEUED, CAPTURING, WRITING, VERIFYING, READY, FAILED, CANCELLED;
    public boolean running() { return this == CAPTURING || this == WRITING || this == VERIFYING; }
    public boolean active() { return this == QUEUED || running(); }
}
