package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.workspace.model.GitReflogProvenanceRecord;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.sql.Connection;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GitReflogProvenanceStoreTest {
    private static final BackupRepositoryKey TARGET = new BackupRepositoryKey("target-repo", "private-target");
    private static final String STORAGE = "explicit-private-target";
    private static final BackupCheckpoint CHECK = () -> { };
    private static final GitReflogProvenance.Origin ORIGIN = new GitReflogProvenance.Origin("a".repeat(64),
            new BackupRepositoryKey("original-repo", "original-workspace"), "original-archive-repository");
    private static final GitHistoryCapture.Reflog ORIGINAL = new GitHistoryCapture.Reflog("refs/heads/deleted", "0".repeat(40),
            "1".repeat(40), "Original Äuthor", "original@example.invalid", 315630245, 120, "Original operation; no target permissions");

    @Test void originalOrderDuplicateEventsAndTimeSurviveCommitAndReopen() throws Exception {
        try (var f = new Fixture()) {
            var events = GitReflogProvenance.original(ORIGIN, List.of(ORIGINAL, ORIGINAL));
            f.retain(events); f.persistence.close();
            var reopened = new GitReflogProvenanceStore(f.database);
            assertThat(reopened.read(TARGET, STORAGE, 10, CHECK)).containsExactlyElementsOf(events);
            assertThat(reopened.read(new BackupRepositoryKey("TARGET-REPO", "private-target"), STORAGE, 10, CHECK)).isEmpty();
            assertThatThrownBy(() -> reopened.read(TARGET, STORAGE, 1, CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void idempotentRetryCannotRewriteAnOriginalEvent() throws Exception {
        try (var f = new Fixture()) {
            var original = GitReflogProvenance.original(ORIGIN, List.of(ORIGINAL, ORIGINAL));
            f.retain(original); f.retain(original);
            var modified = new GitHistoryCapture.Reflog(ORIGINAL.ref(), ORIGINAL.oldId(), ORIGINAL.newId(), ORIGINAL.name(),
                    ORIGINAL.email(), ORIGINAL.epochSeconds() + 1, ORIGINAL.offsetMinutes(), ORIGINAL.message());
            assertThatThrownBy(() -> f.retain(GitReflogProvenance.original(ORIGIN, List.of(ORIGINAL, modified))))
                    .isInstanceOf(IOException.class);
            assertThat(f.store.read(TARGET, STORAGE, 10, CHECK)).containsExactlyElementsOf(original);
        }
    }

    @Test void cancellationAfterActualInsertRollsBackTheWholeEvidenceSet() throws Exception {
        try (var f = new Fixture(); var transaction = f.database.getConnection()) {
            transaction.setAutoCommit(false);
            var events = GitReflogProvenance.original(ORIGIN, List.of(ORIGINAL, ORIGINAL));
            assertThatThrownBy(() -> f.store.retain(transaction, TARGET, STORAGE, events, () -> {
                try (var query = transaction.createStatement(); var rows = query.executeQuery("select count(*) from workspace_git_reflog_provenance")) {
                    rows.next(); if (rows.getInt(1) > 0) throw new InterruptedIOException("Cancelled after durable owner write");
                } catch (java.sql.SQLException problem) { throw new IOException(problem); }
            })).isInstanceOf(InterruptedIOException.class);
            transaction.rollback();
            assertThat(f.store.read(TARGET, STORAGE, 10, CHECK)).isEmpty();
        }
    }

    @Test void storedPayloadAndPhysicalRouteAreVerifiedBeforeReuse() throws Exception {
        try (var f = new Fixture()) {
            var events = GitReflogProvenance.original(ORIGIN, List.of(ORIGINAL)); f.retain(events);
            f.sql("update workspace_git_reflog_provenance set body='{}'");
            assertThatThrownBy(() -> f.store.read(TARGET, STORAGE, 10, CHECK)).isInstanceOf(IOException.class);
        }
        try (var f = new Fixture()) {
            f.retain(GitReflogProvenance.original(ORIGIN, List.of(ORIGINAL)));
            f.sql("update workspace_git_reflog_provenance set storage_repository_name='different-physical-target'");
            assertThatThrownBy(() -> f.store.read(TARGET, STORAGE, 10, CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void completedOwnerReadbackRejectsDeletedEvidenceWithoutReinsertion()throws Exception {
        try(var fixture=new Fixture()) {
            var events=GitReflogProvenance.original(ORIGIN,List.of(ORIGINAL));fixture.retain(events);
            try(var connection=fixture.database.getConnection()) {
                connection.setAutoCommit(false);fixture.store.requireStored(connection,TARGET,STORAGE,events,CHECK);connection.rollback();
            }
            fixture.sql("delete from workspace_git_reflog_provenance");
            try(var connection=fixture.database.getConnection()) {
                connection.setAutoCommit(false);
                assertThatThrownBy(()->fixture.store.requireStored(connection,TARGET,STORAGE,events,CHECK)).isInstanceOf(IOException.class);
                connection.rollback();
            }
            assertThat(fixture.store.read(TARGET,STORAGE,10,CHECK)).isEmpty();
        }
    }

    @Test void incompleteOriginsAndAutocommitTargetsAreRejectedBeforeWrites() throws Exception {
        try (var f = new Fixture(); var connection = f.database.getConnection()) {
            var events = GitReflogProvenance.original(ORIGIN, List.of(ORIGINAL));
            assertThatThrownBy(() -> f.store.retain(connection, TARGET, STORAGE, events, CHECK)).isInstanceOf(IOException.class);
            connection.setAutoCommit(false);
            assertThatThrownBy(() -> f.store.retain(connection, TARGET, STORAGE,
                    List.of(new GitReflogProvenance.Event(ORIGIN, 2, ORIGINAL)), CHECK)).isInstanceOf(IOException.class);
            connection.rollback(); assertThat(f.store.read(TARGET, STORAGE, 10, CHECK)).isEmpty();
        }
    }

    @Test void rollbackFailureCannotHideCancellationOfAnOriginalEvidenceRead()throws Exception {
        try(var f=new Fixture()) {
            var database=new org.springframework.jdbc.datasource.AbstractDataSource() {
                @Override public Connection getConnection()throws java.sql.SQLException {
                    Connection actual=f.database.getConnection();
                    return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                        if(method.getName().equals("rollback"))throw new java.sql.SQLException("fixture rollback unavailable");
                        try{return method.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException wrapped){throw wrapped.getCause();}
                    });
                }
                @Override public Connection getConnection(String user,String password)throws java.sql.SQLException{return getConnection();}
            };
            try {
                assertThatThrownBy(()->new GitReflogProvenanceStore(database).read(TARGET,STORAGE,10,()->{
                    throw new InterruptedIOException("Fixture stopped during owner read");
                })).isInstanceOf(InterruptedIOException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {Thread.interrupted();}
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JDBCDataSource database = new JDBCDataSource(); final EditorPersistenceFixture persistence;
        final GitReflogProvenanceStore store;
        Fixture() {
            String url = "jdbc:hsqldb:mem:git-provenance-" + UUID.randomUUID(); database.setURL(url); database.setUser("SA");
            persistence = new EditorPersistenceFixture(url, GitReflogProvenanceRecord.class);
            store = new GitReflogProvenanceStore(database);
        }
        void retain(List<GitReflogProvenance.Event> events) throws Exception {
            try (Connection connection = database.getConnection()) {
                connection.setAutoCommit(false);
                try { store.retain(connection, TARGET, STORAGE, events, CHECK); connection.commit(); }
                catch (Exception problem) { connection.rollback(); throw problem; }
            }
        }
        void sql(String sql) throws Exception { try (var connection = database.getConnection(); var statement = connection.createStatement()) { statement.executeUpdate(sql); } }
        @Override public void close() { if (!persistence.factory.isClosed()) persistence.close(); }
    }
}
