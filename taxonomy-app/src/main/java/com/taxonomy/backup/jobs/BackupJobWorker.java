package com.taxonomy.backup.jobs;

import com.taxonomy.backup.runtime.BackupAuthorizationService;
import com.taxonomy.backup.archive.*;
import org.springframework.security.access.AccessDeniedException;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** A worker owns one database-fenced claim and only that claim's private files. */
public final class BackupJobWorker {
    private final JdbcBackupJobStore store;
    private final BackupAuthorizationService authorization;
    private final BackupJobStorage storage;
    private final BackupCaptureSource source;
    private final BackupArchiveWriter writer;
    public BackupJobWorker(JdbcBackupJobStore store, BackupAuthorizationService authorization, BackupJobStorage storage,
                           BackupCaptureSource source, BackupArchiveWriter writer) {
        this.store = Objects.requireNonNull(store); this.authorization = Objects.requireNonNull(authorization);
        this.storage = Objects.requireNonNull(storage); this.source = Objects.requireNonNull(source); this.writer = Objects.requireNonNull(writer);
    }
    public boolean runNext() {
        if (Thread.currentThread().isInterrupted()) return false;
        cleanup();
        var next = store.claim(UUID.randomUUID()); if (next.isEmpty()) return false;
        var claim = next.get(); var progress = new Progress(claim); BackupJobState stage = BackupJobState.CAPTURING;
        boolean published = false;
        try {
            progress.force();
            Path work = storage.workDirectory(claim);
            BackupArchiveWriter.PublishedArchive archive;
            try (var captured = source.capture(claim.job().authorization(), work.resolve("spool"), progress)) {
                progress.force(); store.transition(claim, BackupJobState.WRITING); stage = BackupJobState.WRITING; progress.reset();
                archive = writer.write(captured, storage.archiveTarget(claim), progress, () -> {
                    progress.force(); store.transition(claim, BackupJobState.VERIFYING); progress.reset();
                });
            }
            // Capture is deleted before READY releases the temporary-space reservation.
            Files.deleteIfExists(work.resolve("spool"));
            progress.force();
            store.ready(claim, new BackupArtifact(claim.artifactName(), archive.length(), archive.sha256(), archive.encrypted()));
            published = true;
        } catch (IOException | RuntimeException failure) {
            // Never persist or log exception/source text. Interrupt is temporarily cleared only for terminal bookkeeping.
            boolean interrupted = Thread.interrupted();
            try {
                var code = progress.failure != null ? progress.failure : failure instanceof AccessDeniedException ? BackupJobFailure.ACCESS_REVOKED
                        : failure instanceof BackupCapacityException ? BackupJobFailure.STORAGE_FULL
                        : stage == BackupJobState.CAPTURING ? BackupJobFailure.CAPTURE_FAILED : BackupJobFailure.ARCHIVE_INVALID;
                try { store.fail(claim, code); } catch (BackupJobFencedException expired) { store.recoverExpired(); }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        } finally {
            if (!published) {
                boolean interrupted = Thread.interrupted();
                try {
                    storage.discard(claim.artifactName());
                    var job = store.find(claim.job().id());
                    if (job.isPresent() && !job.get().state().active())
                        store.storageCleaned(new JdbcBackupJobStore.Cleanup(claim.job().id(), claim.owner(), claim.attempt(), false));
                } catch (IOException | RuntimeException unavailable) { /* Durable pending reservation is retried by cleanup. */ }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
        return true;
    }
    public void cleanup() {
        store.recoverExpired();
        for (var item : store.cleanupCandidates()) {
            try {
                storage.discard(item.directoryName());
                store.storageCleaned(item);
                if (item.expired()) store.removeExpired(item.id());
            } catch (IOException | BackupJobFencedException unavailable) { /* Another download/node can hold the file; retry on the next sweep. */ }
        }
    }
    private final class Progress implements ArchiveProgress {
        final BackupJobClaim claim;
        final long deadline = System.nanoTime() + Duration.ofHours(1).toNanos();
        long bytes, checked, checkedAt;
        BackupJobFailure failure;
        Progress(BackupJobClaim claim) { this.claim = claim; }
        public void check(long bytes) throws IOException {
            this.bytes = bytes;
            if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) {
                failure = BackupJobFailure.WORKER_EXPIRED; throw interrupted();
            }
            if (System.nanoTime() - checkedAt >= 250_000_000 || bytes - checked >= 4L << 20) force();
        }
        void reset() throws IOException { bytes = 0; checked = 0; checkedAt = 0; force(); }
        void force() throws IOException {
            try {
                authorization.requireJobAccess(claim.job().authorization().principalId(), claim.job().authorization());
                store.heartbeat(claim, bytes); checkedAt = System.nanoTime(); checked = bytes;
            } catch (AccessDeniedException revoked) { failure = BackupJobFailure.ACCESS_REVOKED; throw interrupted(); }
            catch (BackupJobFencedException expired) { failure = BackupJobFailure.WORKER_EXPIRED; throw interrupted(); }
            catch (BackupJobCancelledException cancelled) { failure = BackupJobFailure.CAPTURE_FAILED; throw interrupted(); }
        }
        private InterruptedIOException interrupted() {
            Thread.currentThread().interrupt(); return new InterruptedIOException("Backup processing stopped");
        }
    }
}
