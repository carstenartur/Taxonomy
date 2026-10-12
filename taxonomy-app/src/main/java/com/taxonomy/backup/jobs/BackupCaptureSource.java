package com.taxonomy.backup.jobs;

import com.taxonomy.backup.AuthorizedBackupRequest;
import com.taxonomy.backup.BackupRequest;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.backup.archive.ArchiveProgress;
import com.taxonomy.backup.snapshot.CapturedBackup;
import java.io.IOException;
import java.nio.file.Path;

/** Composition supplies the complete module inventory; each claim has a separate durable spool root. */
@FunctionalInterface
public interface BackupCaptureSource {
    /** Admission policy executes before authorization and durable enqueue. */
    default void validateRequest(BackupRequest request) { }
    default BackupExportOptions exportOptions(PrincipalId actor) { return new BackupExportOptions(java.util.List.of()); }
    CapturedBackup capture(AuthorizedBackupRequest authorization, Path spool, ArchiveProgress progress) throws IOException;
}
