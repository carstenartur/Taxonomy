package com.taxonomy.backup.jobs;

import com.taxonomy.backup.AuthorizedBackupRequest;
import com.taxonomy.backup.archive.ArchiveProgress;
import com.taxonomy.backup.snapshot.CapturedBackup;
import java.io.IOException;
import java.nio.file.Path;

/** Composition supplies the complete module inventory; each claim has a separate durable spool root. */
@FunctionalInterface
public interface BackupCaptureSource {
    CapturedBackup capture(AuthorizedBackupRequest authorization, Path spool, ArchiveProgress progress) throws IOException;
}
