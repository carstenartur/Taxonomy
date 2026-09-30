package com.taxonomy.backup.jobs;

/** Stable public codes only; never persist source text, SQL errors, paths or provider credentials. */
public enum BackupJobFailure { NONE, CAPTURE_FAILED, ARCHIVE_INVALID, ACCESS_REVOKED, WORKER_EXPIRED, QUEUE_EXPIRED, STORAGE_FULL, INTERNAL }
