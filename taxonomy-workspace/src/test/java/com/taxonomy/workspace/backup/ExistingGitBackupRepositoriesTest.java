package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.workspace.storage.DslGitRepository;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.config.CoreEntities;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ExistingGitBackupRepositoriesTest {
    private static final GitTreeCapture.Limits LIMITS = new GitTreeCapture.Limits(100, 1_000_000, 2_000_000);
    private static final BackupCheckpoint CHECK = () -> { };

    @Test void routesTwoCentralRepositoriesAndAPrivateWorkspaceWithoutFallbackOrWrites() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "custom-storage-a", "draft"); f.repository("b", "custom-storage-b", "draft"); f.workspace("a", "private-a", "draft");
            f.commit("custom-storage-a", "CENTRAL-A"); f.commit("custom-storage-b", "CENTRAL-B"); f.commit("ws-private-a", "PRIVATE-A");
            var counts = f.counts(); var stats = f.persistence.factory.getStatistics(); stats.setStatisticsEnabled(true); stats.clear();
            for (var item : Map.of(new BackupRepositoryKey("a", null), "CENTRAL-A", new BackupRepositoryKey("b", null), "CENTRAL-B", new BackupRepositoryKey("a", "private-a"), "PRIVATE-A").entrySet()) {
                try (var source = open(f, current(item.getKey()), item.getKey())) {
                    assertThat(source.key()).isEqualTo(item.getKey()); assertThat(source.branch()).isEqualTo("draft");
                    var captured = source.captureStand(LIMITS, CHECK).orElseThrow();
                    assertThat(content(captured)).isEqualTo(item.getValue());
                }
            }
            assertThat(stats.getEntityInsertCount()).isZero(); assertThat(stats.getEntityUpdateCount()).isZero(); assertThat(stats.getEntityDeleteCount()).isZero();
            assertThat(f.counts()).isEqualTo(counts); assertThat(f.persistence.factory.isOpen()).isTrue();
        }
    }

    @Test void centralScopeNeverAuthorizesItsPrivateWorkspace() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "central-a", "draft"); f.workspace("a", "private-a", "draft"); f.commit("ws-private-a", "PRIVATE");
            assertThatThrownBy(() -> f.sources.open(current(new BackupRepositoryKey("a", null)), new BackupRepositoryKey("a", "private-a"), CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void workspaceCannotBeOpenedUnderAnotherRepositoryIdentity() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "central-a", "draft"); f.repository("b", "central-b", "draft"); f.workspace("b", "private", "draft"); f.commit("ws-private", "PRIVATE-B");
            var wrong = new BackupRepositoryKey("a", "private");
            assertThatThrownBy(() -> f.sources.open(current(wrong), wrong, CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void missingWorkspaceCannotBeSeededFromThePrimaryRepository() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "taxonomy-dsl", "draft"); f.commit("taxonomy-dsl", "PRIMARY-SECRET");
            var counts = f.counts(); var key = new BackupRepositoryKey("a", "absent");
            assertThatThrownBy(() -> f.sources.open(current(key), key, CHECK)).isInstanceOf(IOException.class);
            assertThat(f.counts()).isEqualTo(counts);
        }
    }

    @Test void catalogueRowsDoNotCreateMissingCentralOrWorkspaceStorage() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "not-provisioned", "draft"); f.workspace("a", "not-provisioned", "draft");
            var counts = f.counts();
            for (var key : List.of(new BackupRepositoryKey("a", null), new BackupRepositoryKey("a", "not-provisioned")))
                assertThatThrownBy(() -> f.sources.open(current(key), key, CHECK)).isInstanceOf(IOException.class);
            assertThat(f.counts()).isEqualTo(counts);
        }
    }

    @Test void selectedVersionReadsExactlyItsAuthorizedCommitAndDoesNotExposeLiveBranch() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "central-a", "draft"); String selected = f.commit("central-a", "SELECTED"); f.commit("central-a", "CURRENT");
            var key = new BackupRepositoryKey("a", null);
            var request = new BackupRequest(BackupProfile.SELECTED_VERSION, scope(key), new BackupTime.SelectedVersion(Map.of(key, selected)), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
            try (var source = open(f, authorized(request), key)) {
                assertThat(source.branch()).isNull();
                assertThat(content(source.captureStand(LIMITS, CHECK).orElseThrow())).isEqualTo("SELECTED");
            }
        }
    }

    @Test void missingSelectedCommitDoesNotFallBackToCurrent() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "central-a", "draft"); f.commit("central-a", "CURRENT"); var key = new BackupRepositoryKey("a", null);
            var request = new BackupRequest(BackupProfile.SELECTED_VERSION, scope(key), new BackupTime.SelectedVersion(Map.of(key, "a".repeat(40))), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
            try (var source = open(f, authorized(request), key)) {
                assertThatThrownBy(() -> source.captureStand(LIMITS, CHECK)).isInstanceOf(IOException.class);
            }
        }
    }

    @Test void unbornCurrentBranchIsExplicitlyEmptyEvenWhenAnotherBranchHasFiles() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "central-a", "unborn"); f.commit("central-a", "OTHER-BRANCH"); var key = new BackupRepositoryKey("a", null);
            try (var source = open(f, current(key), key)) { assertThat(source.captureStand(LIMITS, CHECK)).isEmpty(); }
        }
    }

    @Test void historyProfileCannotBeMistakenForACompleteStandCapture() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "central-a", "draft"); f.commit("central-a", "CURRENT"); var key = new BackupRepositoryKey("a", null);
            var request = new BackupRequest(BackupProfile.REPOSITORY_HISTORY, scope(key), new BackupTime.History(), GitRepresentation.BUNDLE, SecretsSelection.EXCLUDE);
            try (var source = open(f, authorized(request), key)) {
                assertThatThrownBy(() -> source.captureStand(LIMITS, CHECK)).isInstanceOf(IOException.class);
            }
        }
    }

    @Test void closedSessionCannotContinueReadingAndDoesNotCloseTheSharedDatabase() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "central-a", "draft"); f.commit("central-a", "CURRENT"); var key = new BackupRepositoryKey("a", null);
            var source = open(f, current(key), key); source.close(); source.close();
            assertThatThrownBy(() -> source.captureStand(LIMITS, CHECK)).isInstanceOf(IOException.class);
            assertThat(f.persistence.factory.isOpen()).isTrue();
            try (var reopened = open(f, current(key), key)) { assertThat(content(reopened.captureStand(LIMITS, CHECK).orElseThrow())).isEqualTo("CURRENT"); }
        }
    }

    @Test void cancellationBeforeOpeningLeavesNoStorageChangesAndPreservesInterruption() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "not-provisioned", "draft"); var counts = f.counts(); var key = new BackupRepositoryKey("a", null);
            try {
                assertThatThrownBy(() -> f.sources.open(current(key), key, () -> { throw new InterruptedIOException("private source information"); }))
                        .isInstanceOf(InterruptedIOException.class).hasMessageNotContaining("private source information");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
            assertThat(f.counts()).isEqualTo(counts);
        }
    }

    @Test void capturedTreeCannotReadPastTheOwningSessionLifetime() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "central-a", "draft"); f.commit("central-a", "CURRENT"); var key = new BackupRepositoryKey("a", null);
            var source = open(f, current(key), key); var captured = source.captureStand(LIMITS, CHECK).orElseThrow(); source.close();
            assertThatThrownBy(() -> content(captured)).isInstanceOf(IOException.class);
        }
    }

    @Test void unsupportedExternalDependenciesHaveASpecificSourceFreeFailure() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "central-a", "draft");
            f.commit("central-a", "version https://git-lfs.github.com/spec/v1\noid sha256:" + "a".repeat(64) + "\nsize 123\n");
            var key = new BackupRepositoryKey("a", null);
            try (var source = open(f, current(key), key)) {
                assertThatThrownBy(() -> source.captureStand(LIMITS, CHECK)).isInstanceOf(IOException.class)
                        .hasMessageContaining("Git LFS contents are not retained").hasCause(null).satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
            }
        }
    }

    @Test void closingTheSessionDuringACachedBlobCopyStopsFurtherPayload() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "central-a", "draft"); f.commit("central-a", "x".repeat(200_000)); var key = new BackupRepositoryKey("a", null);
            try (var source = open(f, current(key), key)) {
                var captured = source.captureStand(LIMITS, CHECK).orElseThrow(); var bytes = new ByteArrayOutputStream(); var calls = new AtomicInteger();
                assertThatThrownBy(() -> captured.copy(captured.files().getFirst(), bytes, () -> {
                    if (calls.incrementAndGet() == 4) source.close();
                })).isInstanceOf(IOException.class);
                assertThat(bytes.size()).isPositive().isLessThan(200_000);
            }
        }
    }

    @Test void anUnbornResultCannotSucceedAfterItsCheckpointClosesTheSession() throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "central-a", "unborn"); f.commit("central-a", "OTHER"); var key = new BackupRepositoryKey("a", null);
            try (var source = open(f, current(key), key)) {
                var calls = new AtomicInteger();
                assertThatThrownBy(() -> source.captureStand(LIMITS, () -> {
                    if (calls.incrementAndGet() == 2) source.close();
                })).isInstanceOf(IOException.class);
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void routingCleanupPreservesCancellationForCurrentAndSelectedVersions(boolean selected) throws Exception {
        try (var f = new Fixture()) {
            f.repository("a", "central-a", "draft"); var key = new BackupRepositoryKey("a", null); boolean[] connected = {false};
            var database = new org.springframework.jdbc.datasource.DelegatingDataSource(f.database) {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                    var connection = super.getConnection(); connected[0] = true;
                    return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class}, (ignored, method, args) -> {
                        if (method.getName().equals("rollback")) throw new java.sql.SQLException("PRIVATE-ROUTING-CREDENTIALS");
                        try { return method.invoke(connection, args); } catch (java.lang.reflect.InvocationTargetException problem) { throw problem.getCause(); }
                    });
                }
            };
            var sources = new ExistingGitBackupRepositories(database, f.persistence.factory);
            var auth = selected ? authorized(new BackupRequest(BackupProfile.SELECTED_VERSION, scope(key), new BackupTime.SelectedVersion(Map.of(key, "a".repeat(40))), GitRepresentation.NONE, SecretsSelection.EXCLUDE)) : current(key);
            try {
                assertThatThrownBy(() -> sources.open(auth, key, () -> { if (connected[0]) throw new InterruptedIOException("PRIVATE-CANCEL"); }))
                        .isInstanceOf(InterruptedIOException.class).hasNoCause().hasMessageNotContaining("PRIVATE");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
        }
    }

    private static ExistingGitBackupRepositories.ReadSession open(Fixture f, AuthorizedBackupRequest auth, BackupRepositoryKey key) throws IOException {
        var source = f.sources.open(auth, key, CHECK); assertThat(source).as("an existing selected Git source must be opened").isNotNull(); return source;
    }
    private static String content(GitTreeCapture capture) throws IOException {
        assertThat(capture.files()).hasSize(1); var bytes = new ByteArrayOutputStream(); capture.copy(capture.files().getFirst(), bytes, CHECK);
        return bytes.toString(StandardCharsets.UTF_8);
    }
    private static BackupScope scope(BackupRepositoryKey key) {
        return key.workspaceId() == null ? new BackupScope.Repositories(Map.of(key.repositoryId(), Set.of())) : new BackupScope.Workspace(key.repositoryId(), key.workspaceId());
    }
    private static AuthorizedBackupRequest current(BackupRepositoryKey key) {
        return authorized(new BackupRequest(BackupProfile.CURRENT_STATE, scope(key), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE));
    }
    private static AuthorizedBackupRequest authorized(BackupRequest request) {
        return new AuthorizedBackupRequest(request, PrincipalId.create(), "checked-for-test", Instant.now(), EnumSet.allOf(BackupCapability.class));
    }
    private static final class Fixture implements AutoCloseable {
        final JDBCDataSource database = new JDBCDataSource(); final EditorPersistenceFixture persistence;
        final ExistingGitBackupRepositories sources;
        Fixture() throws Exception {
            String url = "jdbc:hsqldb:mem:git-capture-" + UUID.randomUUID() + ";hsqldb.tx=mvcc";
            database.setUrl(url); database.setUser("SA"); database.setPassword(""); persistence = new EditorPersistenceFixture(url);
            sql("create table system_repository(repository_id varchar(255) primary key,storage_repository_name varchar(255) unique,default_branch varchar(255))");
            sql("create table user_workspace(workspace_id varchar(255) primary key,source_repository_id varchar(255),current_branch varchar(255))");
            sources = new ExistingGitBackupRepositories(database, persistence.factory);
        }
        void repository(String id, String storage, String branch) throws Exception { sql("insert into system_repository values(?,?,?)", id, storage, branch); }
        void workspace(String repository, String workspace, String branch) throws Exception { sql("insert into user_workspace values(?,?,?)", workspace, repository, branch); }
        String commit(String storage, String content) throws Exception {
            try (var repository = new DslGitRepository(new DefaultHibernateRepositoryFactory(persistence.factory), storage)) {
                return repository.commitDsl("draft", content, "original-author", "source commit");
            }
        }
        void sql(String sql, Object... parameters) throws Exception {
            try (var connection = database.getConnection(); var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]); statement.executeUpdate();
            }
        }
        Map<String, Long> counts() {
            var counts = new TreeMap<String, Long>();
            try (var session = persistence.factory.openSession()) {
                for (var type : CoreEntities.annotatedClasses()) {
                    String name = persistence.factory.getMetamodel().entity(type).getName();
                    counts.put(name, session.createSelectionQuery("select count(e) from " + name + " e", Long.class).getSingleResult());
                }
            }
            return counts;
        }
        @Override public void close() { persistence.close(); }
    }
}
