package com.taxonomy.templates;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Independent application contexts must use the same durable WebDAV authority. */
class DocumentTemplateSharedWebDavLockTest {
    @Test
    void competingApplicationCannotAcquireAnAlreadyLockedTemplate() {
        var database = database();
        try (var first = application(database); var second = application(database)) {
            var one = first.getBean(DocumentTemplateWebDavLockManager.class);
            var two = second.getBean(DocumentTemplateWebDavLockManager.class);
            var lock = one.acquire("decision-report", "alice", "version-a", Duration.ofMinutes(5), null);

            assertThat(two.find("decision-report")).isEqualTo(lock);
            assertThatThrownBy(() -> two.acquire("decision-report", "bob", "version-a", Duration.ofMinutes(5), null))
                    .isInstanceOf(DocumentTemplateWebDavLockManager.LockConflictException.class);
        }
    }

    @Test
    void refreshAdvanceReleaseAndRestartKeepTheExactResourceTokenAndActor() {
        var database = database();
        String token;
        try (var first = application(database); var second = application(database)) {
            var one = first.getBean(DocumentTemplateWebDavLockManager.class);
            var two = second.getBean(DocumentTemplateWebDavLockManager.class);
            var lock = one.acquire("alpha", "alice", "version-a", Duration.ofMinutes(5), null);
            token = lock.token();
            assertThat(two.acquire("alpha", "alice", "ignored", Duration.ofMinutes(10), token).token()).isEqualTo(token);
            assertThatThrownBy(() -> two.acquire("alpha", "bob", "version-a", Duration.ofMinutes(10), token))
                    .isInstanceOf(DocumentTemplateWebDavLockManager.LockConflictException.class);
            two.advance("alpha", token, "alice", "version-b");
            assertThat(one.require("alpha", token, "alice").currentCommit()).isEqualTo("version-b");
            assertThatThrownBy(() -> two.release("alpha", token, "bob"))
                    .isInstanceOf(DocumentTemplateWebDavLockManager.LockConflictException.class);
            assertThat(two.acquire("beta", "bob", "unrelated", Duration.ofMinutes(5), null).resource()).isEqualTo("beta");
        }
        try (var restarted = application(database)) {
            var locks = restarted.getBean(DocumentTemplateWebDavLockManager.class);
            assertThat(locks.require("alpha", token, "alice").currentCommit()).isEqualTo("version-b");
            locks.release("alpha", token, "alice");
            assertThat(locks.find("alpha")).isNull();
            assertThat(locks.find("beta").owner()).isEqualTo("bob");
        }
    }

    @Test
    void databaseExpiryAllowsANewOwnerButRejectsTheOldToken() throws Exception {
        var database = database();
        try (var first = application(database); var second = application(database)) {
            var one = first.getBean(DocumentTemplateWebDavLockManager.class);
            var two = second.getBean(DocumentTemplateWebDavLockManager.class);
            var old = one.acquire("alpha", "alice", "version-a", Duration.ofMinutes(5), null);
            try (var connection = database.getConnection(); var update = connection.createStatement()) {
                update.executeUpdate("update template_webdav_lock set expires_at=0 where resource_id='alpha'");
            }
            var replacement = two.acquire("alpha", "bob", "version-b", Duration.ofMinutes(5), null);
            assertThat(replacement.token()).isNotEqualTo(old.token());
            assertThatThrownBy(() -> one.require("alpha", old.token(), "alice"))
                    .isInstanceOf(DocumentTemplateWebDavLockManager.LockConflictException.class);
        }
    }

    @Test
    void anExpiredRefreshCannotSilentlyCreateANewEditSession() throws Exception {
        var database = database();
        try (var application = application(database)) {
            var locks = application.getBean(DocumentTemplateWebDavLockManager.class);
            var old = locks.acquire("alpha", "alice", "version-a", Duration.ofMinutes(5), null);
            try (var connection = database.getConnection(); var update = connection.createStatement()) {
                update.executeUpdate("update template_webdav_lock set expires_at=0 where resource_id='alpha'");
            }
            assertThatThrownBy(() -> locks.acquire("alpha", "alice", "version-a", Duration.ofMinutes(5), old.token()))
                    .isInstanceOf(DocumentTemplateWebDavLockManager.LockConflictException.class);
            assertThat(locks.find("alpha")).isNull();
        }
    }

    @Test
    void missingSharedFenceFailsClosedAndNeverCreatesAnIndependentLocalLease() throws Exception {
        var database = database();
        try (var first = application(database); var second = application(database)) {
            var one = first.getBean(DocumentTemplateWebDavLockManager.class);
            var two = second.getBean(DocumentTemplateWebDavLockManager.class);
            var lock = one.acquire("alpha", "alice", "version-a", Duration.ofMinutes(5), null);
            try (var connection = database.getConnection(); var update = connection.createStatement()) {
                update.executeUpdate("delete from template_webdav_gate");
            }
            assertThatThrownBy(() -> two.acquire("alpha", "bob", "version-a", Duration.ofMinutes(5), null))
                    .isInstanceOf(DocumentTemplateWebDavLockManager.LockStoreUnavailableException.class);
            assertThatThrownBy(() -> one.find("alpha"))
                    .isInstanceOf(DocumentTemplateWebDavLockManager.LockStoreUnavailableException.class);
            try (var connection = database.getConnection(); var update = connection.createStatement()) {
                update.executeUpdate("insert into template_webdav_gate values (1)");
            }
            assertThat(two.require("alpha", lock.token(), "alice")).isEqualTo(lock);
        }
    }

    @Test
    void failedWriteRetainsTheOriginalLeaseAndExpectedVersion() throws Exception {
        var database = database();
        try (var first = application(database); var second = application(database)) {
            var one = first.getBean(DocumentTemplateWebDavLockManager.class);
            var two = second.getBean(DocumentTemplateWebDavLockManager.class);
            var lock = one.acquire("alpha", "alice", "version-a", Duration.ofMinutes(5), null);
            assertThatThrownBy(() -> two.executeWrite("alpha", lock.token(), "alice", null,
                    expected -> { throw new IOException("validation failed before Git publication"); }))
                    .isInstanceOf(IOException.class);
            assertThat(one.require("alpha", lock.token(), "alice")).isEqualTo(lock);
            String saved = two.executeWrite("alpha", lock.token(), "alice", null,
                    expected -> new DocumentTemplateWebDavLockManager.LockedWriteResult<>(expected, "version-b"));
            assertThat(saved).isEqualTo("version-a");
            assertThat(one.require("alpha", lock.token(), "alice").currentCommit()).isEqualTo("version-b");
        }
    }

    @Test
    void leaseExpiryCannotAdmitASecondWriterDuringTheGitCommit() throws Exception {
        var database = database();
        var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try (var first = application(database); var second = application(database)) {
            var one = first.getBean(DocumentTemplateWebDavLockManager.class);
            var two = second.getBean(DocumentTemplateWebDavLockManager.class);
            var lock = one.acquire("alpha", "alice", "version-a", Duration.ofSeconds(1), null);
            var insideWrite = new CountDownLatch(1);
            var releaseWrite = new CountDownLatch(1);
            var saved = workers.submit(() -> one.executeWrite("alpha", lock.token(), "alice", null, expected -> {
                assertThat(expected).isEqualTo("version-a");
                // The lease will expire while this accepted save owns the fence.
                insideWrite.countDown();
                try {
                    if (!releaseWrite.await(3, TimeUnit.SECONDS)) throw new IOException("test write was not released");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException(failure);
                }
                return new DocumentTemplateWebDavLockManager.LockedWriteResult<>("saved", "version-b");
            }));
            assertThat(insideWrite.await(3, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20)).until(() -> {
                try (var connection = database.getConnection(); var statement = connection.createStatement();
                     var row = statement.executeQuery("values(current_timestamp)")) {
                    row.next();
                    return row.getObject(1, java.time.OffsetDateTime.class).toInstant().isAfter(lock.expiresAt());
                }
            });
            var contender = workers.submit(() -> two.acquire("alpha", "bob", "version-a", Duration.ofMinutes(5), null));
            try {
                assertThatThrownBy(() -> contender.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                releaseWrite.countDown();
            }
            assertThat(saved.get(3, TimeUnit.SECONDS)).isEqualTo("saved");
            assertThatThrownBy(() -> contender.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(DocumentTemplateWebDavLockManager.LockConflictException.class);
            assertThat(two.require("alpha", lock.token(), "alice").currentCommit()).isEqualTo("version-b");
        } finally {
            workers.shutdownNow();
        }
    }

    private static JDBCDataSource database() {
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:webdav-locks-" + UUID.randomUUID());
        database.setUser("SA");
        database.setPassword("");
        return database;
    }

    private static AnnotationConfigApplicationContext application(DataSource database) {
        var application = new AnnotationConfigApplicationContext();
        application.registerBean(DataSource.class, () -> database);
        application.register(DocumentTemplateWebDavLockManager.class);
        application.refresh();
        return application;
    }
}
