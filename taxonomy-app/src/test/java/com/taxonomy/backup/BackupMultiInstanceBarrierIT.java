package com.taxonomy.backup;

import com.taxonomy.backup.snapshot.BackupMaintenanceLease;
import com.taxonomy.backup.snapshot.GuardedBackupDataSource;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/** Two independent coordinators share the database, without JVM-global locks. */
class BackupMultiInstanceBarrierIT {
    JDBCDataSource database;
    JdbcTemplate raw;
    BackupMaintenanceLease first;
    BackupMaintenanceLease second;
    GuardedBackupDataSource firstData;
    GuardedBackupDataSource secondData;
    final BackupScope scope = new BackupScope.Installation();

    @BeforeEach void setUp() {
        database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:barrier-" + UUID.randomUUID() + ";hsqldb.tx=mvcc");
        database.setUser("sa"); raw = new JdbcTemplate(database);
        raw.execute("create table primary_record(id integer primary key, revision integer)");
        raw.update("insert into primary_record values (1, 0)");
        BackupMaintenanceLease.initialize(database);
        first = new BackupMaintenanceLease(database, Duration.ofSeconds(30));
        second = new BackupMaintenanceLease(database, Duration.ofSeconds(30));
        firstData = new GuardedBackupDataSource(database, first);
        secondData = new GuardedBackupDataSource(database, second);
    }

    @Test void maintenanceWaitsForAnAlreadyOpenTransactionOnAnotherInstance() throws Exception {
        try (Connection writer = firstData.getConnection(); var workers = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (var statement = writer.prepareStatement("update primary_record set revision=1 where id=1")) { statement.executeUpdate(); }
            var capture = workers.submit(() -> second.acquire(scope, Duration.ofSeconds(5)));
            assertThatThrownBy(() -> capture.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            writer.commit();
            try (var maintenance = capture.get(5, TimeUnit.SECONDS)) {
                maintenance.checkValid();
                assertThat(new JdbcTemplate(secondData).queryForObject("select revision from primary_record where id=1", Integer.class)).isEqualTo(1);
                assertThatThrownBy(() -> new JdbcTemplate(firstData).update("update primary_record set revision=2 where id=1"))
                        .hasMessageContaining("maintenance");
            }
        }
        new JdbcTemplate(firstData).update("update primary_record set revision=3 where id=1");
        assertThat(raw.queryForObject("select revision from primary_record", Integer.class)).isEqualTo(3);
    }

    @Test void expiredWorkerCannotCommitBufferedChangesAfterCaptureTakesOver() throws Exception {
        try (Connection stale = firstData.getConnection()) {
            stale.setAutoCommit(false);
            try (var statement = stale.createStatement()) { statement.executeUpdate("update primary_record set revision=7 where id=1"); }
            raw.update("update backup_writer_lease set expires_at=0");
            try (var maintenance = second.acquire(scope, Duration.ofSeconds(2))) {
                assertThatThrownBy(stale::commit).isInstanceOf(SQLException.class).hasMessageContaining("fenced");
                assertThat(raw.queryForObject("select revision from primary_record", Integer.class)).isZero();
                maintenance.checkValid();
            }
        }
    }

    @Test void expiredCaptureCannotRenewOrPublishAfterAnotherInstanceResumesWrites() {
        try (var expired = first.acquire(scope, Duration.ofSeconds(2))) {
            long oldGeneration = expired.generation();
            raw.update("update backup_barrier_state set expires_at=0");
            new JdbcTemplate(secondData).update("update primary_record set revision=9 where id=1");
            assertThatThrownBy(expired::checkValid).isInstanceOf(IllegalStateException.class).hasMessageContaining("fenced");
            assertThatThrownBy(expired::renew).isInstanceOf(IllegalStateException.class).hasMessageContaining("fenced");
            try (var next = second.acquire(scope, Duration.ofSeconds(2))) {
                assertThat(next.generation()).isGreaterThan(oldGeneration);
                expired.close(); // An old close must not release the new owner's lease.
                next.checkValid();
            }
        }
    }

    @Test void nestedWriteSectionsKeepOneDurableLeaseUntilTheOuterSectionCloses() {
        try (var outer = first.enter(scope)) {
            try (var nested = first.enter(scope)) {
                assertThat(nested.generation()).isEqualTo(outer.generation());
                assertThat(raw.queryForObject("select count(*) from backup_writer_lease", Integer.class)).isEqualTo(1);
            }
            outer.checkValid();
            assertThat(raw.queryForObject("select count(*) from backup_writer_lease", Integer.class)).isEqualTo(1);
        }
        assertThat(raw.queryForObject("select count(*) from backup_writer_lease", Integer.class)).isZero();
    }

    @Test void autoCommitBatchesAndImplicitCommitAreFencedAndUnwrapCannotEscape() throws Exception {
        try (var writer = firstData.getConnection()) {
            assertThat(writer.equals(writer)).as("JDBC proxy identity must be reflexive").isTrue();
            assertThat(writer.unwrap(Connection.class)).isSameAs(writer);
            assertThat(writer.getMetaData().getConnection()).isSameAs(writer);
            try (var statement = writer.prepareStatement("update primary_record set revision=? where id=1")) {
                assertThat(statement.getConnection()).isSameAs(writer);
                statement.setInt(1, 1); statement.addBatch(); statement.setInt(1, 2); statement.addBatch();
                statement.executeBatch();
            }
            assertThat(raw.queryForObject("select revision from primary_record", Integer.class)).isEqualTo(2);
            writer.setAutoCommit(false);
            try (var statement = writer.createStatement()) { statement.executeUpdate("update primary_record set revision=4 where id=1"); }
            raw.update("update backup_writer_lease set expires_at=0");
            assertThatThrownBy(() -> writer.setAutoCommit(true)).isInstanceOf(SQLException.class).hasMessageContaining("fenced");
            assertThat(raw.queryForObject("select revision from primary_record", Integer.class)).isEqualTo(2);
        }
    }

    @Test void fullApplicationConnectionPoolDoesNotStarveLeaseCoordination() throws Exception {
        var configuration = new com.zaxxer.hikari.HikariConfig();
        configuration.setJdbcUrl(database.getUrl()); configuration.setUsername("sa");
        configuration.setMaximumPoolSize(1); configuration.setMinimumIdle(0); configuration.setConnectionTimeout(300);
        try (var application = new com.zaxxer.hikari.HikariDataSource(configuration);
             var coordination = com.taxonomy.backup.snapshot.BackupCoordinationPool.open(application)) {
            var leases = new BackupMaintenanceLease(coordination, Duration.ofSeconds(30));
            var guarded = new GuardedBackupDataSource(application, leases);
            try (var connection = guarded.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) { statement.executeUpdate("update primary_record set revision=5 where id=1"); }
                connection.commit();
            }
            try (var captured = leases.acquire(scope, Duration.ofSeconds(2))) {
                captured.checkValid();
                assertThat(raw.queryForObject("select revision from primary_record", Integer.class)).isEqualTo(5);
            }
        }
    }

    @Test void explicitSqlCommitCannotPublishAnUnfencedTransaction() throws Exception {
        try (var connection = firstData.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("update primary_record set revision=8 where id=1");
            assertThatThrownBy(() -> statement.execute("/* native query */ COMMIT"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("startup maintenance");
            connection.rollback();
            assertThat(raw.queryForObject("select revision from primary_record", Integer.class)).isZero();
        }
    }

    @Test void nativeProcedureCallsCannotEscapeTheTransactionFence() throws Exception {
        try (var connection = firstData.getConnection(); var call = connection.prepareCall("CALL 1")) {
            assertThatThrownBy(call::execute).isInstanceOf(SQLException.class).hasMessageContaining("startup maintenance");
            try (var statement = connection.createStatement()) {
                for (String sql : java.util.List.of("{call 1}", "SELECT 1; UPDATE primary_record SET revision=9", "SELECT * INTO copied FROM primary_record"))
                    assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(SQLException.class).hasMessageContaining("startup maintenance");
            }
        }
    }

    @Test void lobLocatorsAndMetadataResultSetsCannotExposeAnUnguardedWriteHandle() throws Exception {
        raw.execute("create table blob_evidence(id integer primary key, content blob)");
        raw.update("insert into blob_evidence values(1, ?)", new byte[]{1, 2, 3});
        try (var connection = firstData.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("select content from blob_evidence")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getStatement()).isSameAs(statement);
            assertThatThrownBy(() -> rows.getBlob(1).setBytes(1, new byte[]{9}))
                    .isInstanceOf(SQLException.class).hasMessageContaining("guarded prepared statement");
            try (var tables = connection.getMetaData().getTables(null, null, "BLOB_EVIDENCE", null)) {
                assertThat(tables.unwrap(java.sql.ResultSet.class)).isSameAs(tables);
            }
        }
    }

    @Test void startupSchemaHoldCannotExpireIntoAConcurrentCapture() throws Exception {
        var startup = first.beginStartup(Duration.ofSeconds(2));
        var bootData = new GuardedBackupDataSource(database, first, startup);
        raw.update("update backup_barrier_state set expires_at=0");
        assertThatThrownBy(() -> second.acquire(scope, Duration.ofMillis(100)))
                .hasMessageContaining("maintenance");
        assertThatThrownBy(() -> new JdbcTemplate(secondData).update("update primary_record set revision=6 where id=1"))
                .hasMessageContaining("maintenance");
        new JdbcTemplate(bootData).execute("create table boot_record(id integer primary key)");
        new JdbcTemplate(bootData).update("insert into boot_record values(1)");
        bootData.finishStartup();
        try (var captured = second.acquire(scope, Duration.ofSeconds(2))) {
            captured.checkValid();
            assertThat(raw.queryForObject("select count(*) from boot_record", Integer.class)).isEqualTo(1);
        }
        assertThatThrownBy(() -> new JdbcTemplate(bootData).execute("create table runtime_ddl(id integer)"))
                .hasMessageContaining("startup maintenance");
    }

    @Test void startupDoesNotReleaseItsHoldWhenApplicationInitializationFails() {
        first.beginStartup(Duration.ofSeconds(2)); // Simulated crash before explicit success.
        raw.update("update backup_barrier_state set expires_at=0");
        var restarted = new BackupMaintenanceLease(database, Duration.ofSeconds(30));
        assertThatThrownBy(() -> restarted.beginStartup(Duration.ofMillis(100))).hasMessageContaining("maintenance");
        assertThatThrownBy(() -> restarted.acquire(scope, Duration.ofMillis(100))).hasMessageContaining("maintenance");
    }
}
