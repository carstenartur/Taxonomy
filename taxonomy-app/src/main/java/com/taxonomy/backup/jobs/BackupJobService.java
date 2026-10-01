package com.taxonomy.backup.jobs;

import com.taxonomy.backup.*;
import org.springframework.security.access.AccessDeniedException;
import java.io.IOException;
import java.util.*;

/** Every externally visible operation rechecks current grants and the stable principal, including status. */
public final class BackupJobService {
    private final JdbcBackupJobStore store;
    private final BackupAuthorizationService authorization;
    private final BackupJobStorage storage;
    private final boolean encrypted;
    public BackupJobService(JdbcBackupJobStore store, BackupAuthorizationService authorization, BackupJobStorage storage, boolean encrypted) {
        this.store = Objects.requireNonNull(store); this.authorization = Objects.requireNonNull(authorization);
        this.storage = Objects.requireNonNull(storage); this.encrypted = encrypted;
    }
    public BackupJobId submit(BackupRequest request, PrincipalId actor) {
        var authorized = authorization.authorize(actor, request);
        if (request.secrets() == SecretsSelection.INCLUDE_ENCRYPTED && !encrypted) throw new IllegalStateException("Backup protection is unavailable");
        return store.enqueue(authorized);
    }
    public boolean cancel(BackupJobId id, PrincipalId actor) { require(id, actor); return store.cancel(id, actor); }
    public JobView status(BackupJobId id, PrincipalId actor) { return view(require(id, actor), actor); }
    public List<JobView> list(PrincipalId actor) {
        var visible = new ArrayList<JobView>();
        for (var job : store.list(actor)) {
            try { authorization.requireJobAccess(actor, job.authorization()); visible.add(view(job, actor)); }
            catch (AccessDeniedException revoked) { /* Omit metadata whose scope is no longer readable. */ }
            if (visible.size() >= 50) break;
        }
        return List.copyOf(visible);
    }
    public BackupJobStorage.Download download(BackupJobId id, PrincipalId actor) throws IOException {
        var job = require(id, actor); authorization.requireDownload(actor, job.authorization());
        if (job.state() != BackupJobState.READY || job.artifact() == null || !store.retained(id)) throw new IllegalStateException("Backup download is unavailable");
        return storage.open(job.artifact(), "backup-" + id.value() + ".taxbackup", () -> {
            authorization.requireDownload(actor, job.authorization());
            if (!store.retained(id)) throw new IllegalStateException("Backup download is unavailable");
        });
    }
    private BackupJob require(BackupJobId id, PrincipalId actor) {
        var job = store.find(id).orElseThrow(() -> new AccessDeniedException("Backup access denied"));
        authorization.requireJobAccess(actor, job.authorization()); return job;
    }
    private JobView view(BackupJob job, PrincipalId actor) {
        boolean download = job.state() == BackupJobState.READY && store.retained(job.id());
        if (download) try { authorization.requireDownload(actor, job.authorization()); } catch (AccessDeniedException denied) { download = false; }
        return new JobView(job.id().value().toString(), job.authorization().request().profile(), job.state(), job.progressBytes(),
                job.cancellationRequested(), job.createdAt().toString(), job.expiresAt().toString(), download,
                job.artifact() != null && job.artifact().encrypted(), job.failure());
    }
    /** Allowlisted public fields; internal authorization, source identifiers, filenames and claim tokens never escape. */
    public record JobView(String id, BackupProfile profile, BackupJobState state, long progressBytes, boolean cancellationRequested,
                          String createdAt, String expiresAt, boolean downloadAvailable, boolean encrypted, BackupJobFailure failure) { }
}
