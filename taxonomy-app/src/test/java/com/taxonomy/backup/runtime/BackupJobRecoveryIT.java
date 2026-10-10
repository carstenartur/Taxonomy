package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.backup.jobs.*;
import com.taxonomy.backup.snapshot.BackupMaintenanceLease;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class BackupJobRecoveryIT {
    JDBCDataSource database;
    JdbcTemplate raw;
    JdbcBackupJobStore first, second;
    final BackupJobLimits limits = new BackupJobLimits(1, 2, 16, Duration.ofSeconds(30), Duration.ofHours(24), 64L << 30);

    @BeforeEach void prepare() {
        database = new JDBCDataSource(); database.setUrl("jdbc:hsqldb:mem:backup-jobs-" + UUID.randomUUID() + ";hsqldb.tx=mvcc"); database.setUser("sa");
        BackupMaintenanceLease.initialize(database);
        first = new JdbcBackupJobStore(database, limits); second = new JdbcBackupJobStore(database, limits); raw = new JdbcTemplate(database);
    }

    @Test void restartRetainsQueueAndTwoInstancesCannotClaimTheSameJob() throws Exception {
        var authorization = authorized(false, PrincipalId.create()); var id = first.enqueue(authorization);
        var restarted = new JdbcBackupJobStore(database, limits);
        assertThat(restarted.find(id).orElseThrow().authorization()).isEqualTo(authorization);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = threads.submit(() -> { start.await(); return first.claim(UUID.randomUUID()); });
            var b = threads.submit(() -> { start.await(); return second.claim(UUID.randomUUID()); });
            start.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)).stream().filter(Optional::isPresent)).hasSize(1);
        }
        assertThat(first.find(id).orElseThrow().state()).isEqualTo(BackupJobState.CAPTURING);
    }

    @Test void heavyCapacityDoesNotBlockTwoSmallJobsAndOldestHeavyEventuallyRuns() {
        var heavy1 = first.enqueue(authorized(true, PrincipalId.create()));
        var heavy2 = first.enqueue(authorized(true, PrincipalId.create()));
        var small1 = first.enqueue(authorized(false, PrincipalId.create()));
        var small2 = first.enqueue(authorized(false, PrincipalId.create()));
        var small3 = first.enqueue(authorized(false, PrincipalId.create()));
        var activeHeavy = first.claim(UUID.randomUUID()).orElseThrow();
        assertThat(activeHeavy.job().id()).isEqualTo(heavy1);
        assertThat(second.claim(UUID.randomUUID()).orElseThrow().job().id()).isEqualTo(small1);
        assertThat(first.claim(UUID.randomUUID()).orElseThrow().job().id()).isEqualTo(small2);
        assertThat(second.claim(UUID.randomUUID())).isEmpty();
        first.fail(activeHeavy, BackupJobFailure.CAPTURE_FAILED);
        first.storageCleaned(new JdbcBackupJobStore.Cleanup(activeHeavy.job().id(), activeHeavy.owner(), activeHeavy.attempt(), false));
        assertThat(second.claim(UUID.randomUUID()).orElseThrow().job().id()).isEqualTo(heavy2);
        assertThat(first.find(small3).orElseThrow().state()).isEqualTo(BackupJobState.QUEUED);
    }

    @Test void principalAndQueueLimitsApplyAtomicallyAcrossInstances() {
        var principal = PrincipalId.create(); var id = first.enqueue(authorized(false, principal));
        assertThatThrownBy(() -> second.enqueue(authorized(true, principal))).isInstanceOf(BackupCapacityException.class);
        first.cancel(id, principal);
        assertThatCode(() -> second.enqueue(authorized(true, principal))).doesNotThrowAnyException();
        for (int i = 1; i < limits.queueCapacity(); i++) first.enqueue(authorized(false, PrincipalId.create()));
        assertThatThrownBy(() -> second.enqueue(authorized(false, PrincipalId.create()))).isInstanceOf(BackupCapacityException.class);
    }

    @Test void expiredWorkerCannotRenewTransitionOrPublishAfterRecovery() {
        var id = first.enqueue(authorized(false, PrincipalId.create())); var lost = first.claim(UUID.randomUUID()).orElseThrow();
        raw.update("update backup_job set lease_until=0 where job_id=?", id.value().toString());
        assertThat(second.recoverExpired()).extracting(BackupJob::id).containsExactly(id);
        assertThat(first.find(id).orElseThrow().failure()).isEqualTo(BackupJobFailure.WORKER_EXPIRED);
        assertThatThrownBy(() -> first.heartbeat(lost, 5)).isInstanceOf(BackupJobFencedException.class);
        assertThatThrownBy(() -> first.transition(lost, BackupJobState.WRITING)).isInstanceOf(BackupJobFencedException.class);
        assertThatThrownBy(() -> first.ready(lost, artifact(lost))).isInstanceOf(BackupJobFencedException.class);
    }

    @Test void onlyVerifiedOwnerCanPublishAndCancellationHoldsCapacityUntilAcknowledged() {
        var principal = PrincipalId.create(); var id = first.enqueue(authorized(true, principal));
        var claim = first.claim(UUID.randomUUID()).orElseThrow();
        assertThatThrownBy(() -> first.ready(claim, artifact(claim))).isInstanceOf(IllegalStateException.class);
        first.transition(claim, BackupJobState.WRITING); first.transition(claim, BackupJobState.VERIFYING);
        assertThatThrownBy(() -> second.cancel(id, PrincipalId.create())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        second.cancel(id, principal);
        assertThat(first.find(id).orElseThrow().cancellationRequested()).isTrue();
        assertThatThrownBy(() -> first.ready(claim, artifact(claim))).isInstanceOf(BackupJobCancelledException.class);
        var queued = second.enqueue(authorized(true, PrincipalId.create()));
        assertThat(second.claim(UUID.randomUUID())).isEmpty();
        first.fail(claim, BackupJobFailure.CAPTURE_FAILED);
        assertThat(first.find(id).orElseThrow().state()).isEqualTo(BackupJobState.CANCELLED);
        assertThat(second.claim(UUID.randomUUID()).orElseThrow().job().id()).isEqualTo(queued);
    }

    @Test void readyArtifactMetadataAndRetentionSurviveRestart() {
        var auth = authorized(false, PrincipalId.create()); var id = first.enqueue(auth); var claim = first.claim(UUID.randomUUID()).orElseThrow();
        first.transition(claim, BackupJobState.WRITING); first.transition(claim, BackupJobState.VERIFYING);
        first.ready(claim, artifact(claim));
        var durable = second.find(id).orElseThrow();
        assertThat(durable.state()).isEqualTo(BackupJobState.READY);
        assertThat(durable.artifact()).isEqualTo(artifact(claim));
        assertThat(durable.expiresAt()).isAfter(durable.updatedAt());
        assertThat(second.cancel(id, auth.principalId())).isFalse();
        raw.update("update backup_job set expires_at=0 where job_id=?", id.value().toString());
        assertThat(second.expiredArtifacts()).extracting(BackupJob::id).containsExactly(id);
        second.removeExpired(id);
        assertThat(first.find(id)).isEmpty();
    }

    @Test void artifactQuotaRejectsPublicationWithoutTrustingSubmittedPaths() {
        var constrained = new JdbcBackupJobStore(database, new BackupJobLimits(1, 2, 16, Duration.ofSeconds(30), Duration.ofHours(24), 1));
        constrained.enqueue(authorized(false, PrincipalId.create())); var claim = constrained.claim(UUID.randomUUID()).orElseThrow();
        constrained.transition(claim, BackupJobState.WRITING); constrained.transition(claim, BackupJobState.VERIFYING);
        assertThatThrownBy(() -> constrained.ready(claim, artifact(claim))).isInstanceOf(BackupCapacityException.class);
        assertThatThrownBy(() -> new BackupArtifact("../elsewhere.taxbackup", 5, "a".repeat(64), false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void jobHeartbeatsAndCancellationRemainAvailableDuringCaptureMaintenance() {
        var actor = PrincipalId.create(); first.enqueue(authorized(false, actor));
        var claim = first.claim(UUID.randomUUID()).orElseThrow();
        var barrier = new BackupMaintenanceLease(database, Duration.ofSeconds(30));
        try (var capture = barrier.acquire(new BackupScope.Installation(), Duration.ofSeconds(1))) {
            second.heartbeat(claim, 42);
            assertThat(first.find(claim.job().id()).orElseThrow().progressBytes()).isEqualTo(42);
            first.cancel(claim.job().id(), actor);
            assertThatThrownBy(() -> second.heartbeat(claim, 43)).isInstanceOf(BackupJobCancelledException.class);
            capture.checkValid();
        }
    }

    @Test void temporaryStorageReservationsBoundCombinedConcurrency() {
        var constrained = new JdbcBackupJobStore(database, new BackupJobLimits(1, 2, 16, Duration.ofSeconds(30),
                Duration.ofHours(24), 64L << 30, BackupJobLimits.TEMPORARY_BYTES_PER_JOB));
        constrained.enqueue(authorized(false, PrincipalId.create())); constrained.enqueue(authorized(false, PrincipalId.create()));
        var active = constrained.claim(UUID.randomUUID()).orElseThrow();
        assertThat(constrained.claim(UUID.randomUUID())).isEmpty();
        constrained.fail(active, BackupJobFailure.CAPTURE_FAILED);
        assertThat(constrained.claim(UUID.randomUUID())).as("failed files retain their storage reservation until cleanup").isEmpty();
        constrained.storageCleaned(new JdbcBackupJobStore.Cleanup(active.job().id(), active.owner(), active.attempt(), false));
        assertThat(constrained.claim(UUID.randomUUID())).isPresent();
    }

    @Test void queuedJobsExpireWithoutClaimingStorageOrPermanentlyBlockingThePrincipal() {
        var actor = PrincipalId.create(); var id = first.enqueue(authorized(false, actor));
        raw.update("update backup_job set expires_at=0 where job_id=?", id.value().toString());
        assertThat(second.claim(UUID.randomUUID())).isEmpty();
        assertThat(first.find(id).orElseThrow().state()).isEqualTo(BackupJobState.FAILED);
        assertThat(first.find(id).orElseThrow().failure().name()).isEqualTo("QUEUE_EXPIRED");
        assertThatCode(() -> first.enqueue(authorized(false, actor))).doesNotThrowAnyException();
    }

    private BackupArtifact artifact(BackupJobClaim claim) { return new BackupArtifact(claim.artifactName(), 5, "a".repeat(64), false); }
    private AuthorizedBackupRequest authorized(boolean history, PrincipalId actor) {
        BackupRequest request = new BackupRequest(history ? BackupProfile.REPOSITORY_HISTORY : BackupProfile.CURRENT_STATE,
                new BackupScope.Workspace("repo", "workspace"), history ? new BackupTime.History() : new BackupTime.Current(),
                history ? GitRepresentation.BUNDLE : GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        return new AuthorizedBackupRequest(request, actor, UUID.randomUUID().toString(), Instant.now(), EnumSet.allOf(BackupCapability.class));
    }
}
