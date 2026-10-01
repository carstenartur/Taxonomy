package com.taxonomy.backup;

import com.taxonomy.backup.jobs.*;
import com.taxonomy.backup.snapshot.BackupMaintenanceLease;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class BackupJobReadOnlyTest {
    private final PrincipalId actor = PrincipalId.create();
    private ObservedDataSource observed;
    private JdbcTemplate raw;
    private JdbcBackupJobStore store;
    private BackupJobId id;

    @BeforeEach void prepare() {
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:backup-job-reads-" + UUID.randomUUID() + ";hsqldb.tx=mvcc");
        database.setUser("sa");
        BackupMaintenanceLease.initialize(database);
        var limits = new BackupJobLimits(1, 2, 16, Duration.ofSeconds(30), Duration.ofHours(24), 64L << 30);
        var writer = new JdbcBackupJobStore(database, limits);
        var request = new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Workspace("repo", "workspace"),
                new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        id = writer.enqueue(new AuthorizedBackupRequest(request, actor, "read-only-test", Instant.now(),
                Set.of(BackupCapability.EXPORT_CURRENT)));
        var claim = writer.claim(UUID.randomUUID()).orElseThrow();
        writer.transition(claim, BackupJobState.WRITING);
        writer.transition(claim, BackupJobState.VERIFYING);
        writer.ready(claim, new BackupArtifact(claim.artifactName(), 5, "a".repeat(64), false));
        raw = new JdbcTemplate(database);
        observed = new ObservedDataSource(database);
        store = new JdbcBackupJobStore(observed, limits);
        observed.connections.clear();
    }

    enum Query { FIND, LIST, RETAINED, EXPIRED_ARTIFACTS, CLEANUP_CANDIDATES }

    @ParameterizedTest @EnumSource(Query.class)
    void readsUseReadOnlyConnectionsAndPreserveQueueState(Query query) throws Exception {
        if (query == Query.EXPIRED_ARTIFACTS || query == Query.CLEANUP_CANDIDATES)
            raw.update("update backup_job set expires_at=0 where job_id=?", id.value().toString());
        var before = raw.queryForMap("select job_state, updated_at, expires_at, artifact_name from backup_job");
        switch (query) {
            case FIND -> assertThat(store.find(id).orElseThrow().id()).isEqualTo(id);
            case LIST -> assertThat(store.list(actor)).extracting(BackupJob::id).containsExactly(id);
            case RETAINED -> assertThat(store.retained(id)).isTrue();
            case EXPIRED_ARTIFACTS -> assertThat(store.expiredArtifacts()).extracting(BackupJob::id).containsExactly(id);
            case CLEANUP_CANDIDATES -> assertThat(store.cleanupCandidates()).extracting(JdbcBackupJobStore.Cleanup::id).containsExactly(id);
        }
        assertThat(observed.readOnlyAtQuery).isNotEmpty().containsOnly(true);
        assertThat(raw.queryForMap("select job_state, updated_at, expires_at, artifact_name from backup_job")).isEqualTo(before);
        assertConnectionsClosed();
        assertThat(store.cancel(id, actor)).isFalse();
    }

    @Test void sqlFailureClosesTheReadConnectionWithoutLeakingDatabaseDetails() throws Exception {
        observed.failQuery = true;
        assertThatThrownBy(() -> store.find(id)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Backup job storage unavailable").hasNoCause();
        assertConnectionsClosed();
    }

    @Test void malformedAuthorizationClosesTheReadConnectionWithoutChangingTheJob() throws Exception {
        raw.update("update backup_job set authorization_json='invalid' where job_id=?", id.value().toString());
        assertThatThrownBy(() -> store.find(id)).isInstanceOf(IllegalArgumentException.class);
        assertConnectionsClosed();
        assertThat(raw.queryForObject("select job_state from backup_job", String.class)).isEqualTo("READY");
    }

    private void assertConnectionsClosed() throws SQLException {
        assertThat(observed.connections).isNotEmpty();
        for (var connection : observed.connections) assertThat(connection.isClosed()).isTrue();
    }

    /** Observe real HSQLDB connection state; all SQL and transaction handling still run against the database. */
    private static final class ObservedDataSource extends DelegatingDataSource {
        final List<Connection> connections = new ArrayList<>();
        final List<Boolean> readOnlyAtQuery = new ArrayList<>();
        boolean failQuery;

        ObservedDataSource(DataSource delegate) { super(delegate); }

        @Override public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            connections.add(connection);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement")) {
                            readOnlyAtQuery.add(connection.isReadOnly());
                            if (failQuery) throw new SQLException("private database details");
                        }
                        try { return method.invoke(connection, arguments); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }
}
