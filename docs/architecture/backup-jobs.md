# Durable portable-backup jobs

P04 supplies the job, archive and HTTP boundary. Composition enables processing
only when a complete `BackupCaptureSource` is registered (P05/P06). With the
feature disabled, no worker or public backup controller starts. A missing source
returns an explicit unavailable response instead of accepting unprocessable jobs.

## Ownership and lifecycle

`backup_job_queue` serializes admission and claims across every instance sharing
the application database. `backup_job` records the stable principal, bounded
request/authorization document, enqueue sequence, claim owner/attempt, database
lease, state, progress and an allowlisted artifact receipt. These tables belong
to the backup control schema's V2 Java migration and are transient export state;
a restore never reactivates their claims or downloads.

Both this queue and the capture barrier obtain a write lock before reading the
coordination row. A no-value-change UPDATE is necessary because HSQLDB MVCC does
not acquire an exclusive row lock with SELECT FOR UPDATE alone. The private
coordination pool remains available while business writes are paused.

Claims follow the oldest eligible enqueue sequence. Defaults allow one heavy
history/installation job and two current/selected-state jobs across the whole
installation, one active export per principal, and 64 queued jobs. An occupied
heavy lane does not prevent eligible current jobs from progressing. A queued
request expires after the configured retention interval. Active workers check
current authority, interruption, elapsed time and lease ownership at phase
boundaries, at most every 250 ms or 4 MiB of streaming progress. A worker has a
one-hour total deadline; capture and archive each have their own 30-minute budget.

A callback enters VERIFYING before archive verification begins. Only the same
non-expired owner can publish READY after close, force, full archive verification,
atomic rename and deletion of the captured spool. Cancellation keeps the running
slot until acknowledgement or lease expiry. Lost workers become FAILED, rather
than silently replaying partially completed work. All terminal errors are fixed
codes; exception messages, source payloads, credentials and filesystem paths are
never persisted in job status or passed to the UI.

## Volume, quotas and cleanup

All serving nodes need the **same durable application-private backup volume** at
`TAXONOMY_BACKUP_DIRECTORY`, supporting atomic rename. Relative default
`./data/backups` must be placed on persistent shared storage in a multi-instance
deployment. No external process may alter its files. The application creates
private directories and files on POSIX; Windows deployments must restrict the
volume's inherited ACL to the application account and administrators.

Each claim owns a distinct job/owner/attempt directory. Worker lease expiry does
not allow the old worker to overwrite or delete a successor's files. No archive
supplies a filesystem destination, executable hook, provider key or download URL.

A claim reserves 24 GiB: the 10 GiB capture, bounded protection/manifest overhead,
and the maximum 12 GiB archive fit within that reservation. The default temporary
quota is 72 GiB. Failed jobs retain their reservation until their private files
have actually been deleted; failed cleanup cannot create unbounded new writers.
READY archives have an independent 64 GiB quota, with a default 24-hour retention.
Expired files count toward it until deletion succeeds. Disk-full and interrupted
writes cannot become downloadable READY results.

Periodic worker sweeps recover expired claims, remove partial files and delete
expired artifacts/metadata in bounded batches. Cleanup tolerates an in-progress
download holding a file open on Windows and retries later. Source capture limits
must remain within the reservation when configuring an alternative adapter.

## HTTP, permissions and UI

`POST /api/backups/jobs` accepts the explicit JSON BackupRequest wire format
(maximum 64 KiB). GET collection/item/download and POST item/cancel share the
same stable-principal resolver and current authorization service. Unknown API
write paths remain denied. Browser requests retain the existing CSRF protection.
All status and download responses use `Cache-Control: no-store`.

Only the owning principal with current scope/profile rights can see metadata.
Download also requires DOWNLOAD_BACKUP, checked at open and during transfer.
One descriptor is used for receipt verification and delivery, so replacement of
a filename cannot substitute another file. The browser receives a generated
`backup-<job UUID>.taxbackup` name, never a source name or claim token.

`/backup-jobs` renders English/German status, progress, retention, cancellation
and native streamed downloads. It bounds the recent list to 50, renders ten
jobs per page, preserves focused controls while updating fields, and pauses
polling while the page is hidden. Product export selection and real-browser
acceptance are completed with the module adapters and P12.

See [configuration reference](../en/CONFIGURATION_REFERENCE.md) and the
[protection ADR](backup-archive-protection.md) for key custody. Provision Tink's
AES256_GCM_HKDF_1MB keyset outside this volume, preferably through a read-only
secret-store mount. Keep old decryption keys during rotation and independently
back them up. The application never creates ephemeral replacement production keys.
