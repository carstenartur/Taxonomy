package com.taxonomy.composition.backup;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class BoundedBackupMetadataTest {
    @Test void actualQueryAcceptsExactBoundaryAndRejectsEveryOverflowInsteadOfTruncating() throws Exception {
        var database = database(); var metadata = new BoundedBackupMetadata(database);
        var checks = new AtomicInteger();
        var exact = metadata.read("select repository_id from system_repository order by repository_id", List.of(),
                3, checks::incrementAndGet, row -> row.getString(1));
        assertThat(exact).containsExactly("fixture-a", "fixture-b", "fixture-c");
        assertThat(checks.get()).isGreaterThanOrEqualTo(6); // before query, every row and completion
        try (var c = database.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("insert into system_repository values ('fixture-d')");
        }
        assertThatThrownBy(() -> metadata.read("select repository_id from system_repository order by repository_id",
                List.of(), 3, () -> {}, row -> row.getString(1))).isInstanceOf(IOException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> metadata.read("select repository_id from system_repository order by repository_id",
                List.of(), 0, () -> {}, row -> row.getString(1))).isInstanceOf(IOException.class).hasMessageContaining("limit");
    }
    @Test void parametersPreserveExactSelectionAndCancellationStopsPerRow() throws Exception {
        var metadata = new BoundedBackupMetadata(database());
        assertThat(metadata.read("select repository_id from system_repository where repository_id=?", List.of("fixture-b"),
                1, () -> {}, row -> row.getString(1))).containsExactly("fixture-b");
        var checks = new AtomicInteger();
        assertThatThrownBy(() -> metadata.read("select repository_id from system_repository order by repository_id", List.of(),
                3, () -> { if (checks.incrementAndGet() == 4) throw new InterruptedIOException("fixture cancellation"); }, row -> row.getString(1)))
                .isInstanceOf(InterruptedIOException.class);
        Thread.interrupted();
    }
    @Test void rollbackFailurePreservesCancellationAndInterruptWithoutLeakingSourceDetails() throws Exception {
        var actual = database(); var checks = new AtomicInteger();
        var database = new org.springframework.jdbc.datasource.DelegatingDataSource(actual) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = super.getConnection();
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class}, (ignored, method, args) -> {
                            if (method.getName().equals("rollback")) throw new java.sql.SQLException("PRIVATE-ROLLBACK-CREDENTIALS");
                            try { return method.invoke(connection, args); }
                            catch (java.lang.reflect.InvocationTargetException problem) { throw problem.getCause(); }
                        });
            }
        };
        try {
            assertThatThrownBy(() -> new BoundedBackupMetadata(database).read(
                    "select repository_id from system_repository order by repository_id", List.of(), 3,
                    () -> { if (checks.incrementAndGet() == 3) throw new InterruptedIOException("PRIVATE-CANCEL"); }, row -> row.getString(1)))
                    .isInstanceOf(InterruptedIOException.class).hasNoCause().hasMessageNotContaining("PRIVATE");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
    private static JDBCDataSource database() throws Exception {
        var database = new JDBCDataSource(); database.setURL("jdbc:hsqldb:mem:backup-metadata-" + UUID.randomUUID()); database.setUser("sa");
        try (var c = database.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("create table system_repository(repository_id varchar(100) primary key)");
            s.executeUpdate("insert into system_repository values ('fixture-a'),('fixture-b'),('fixture-c')");
        }
        return database;
    }
}
