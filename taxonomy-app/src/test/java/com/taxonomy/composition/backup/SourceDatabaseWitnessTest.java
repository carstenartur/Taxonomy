package com.taxonomy.composition.backup;

import com.taxonomy.backup.*;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import java.io.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SourceDatabaseWitnessTest {
    @Test void witnessesActualVendorAndNeverReadsConnectionUrlsOrUsers() throws Exception {
        var checks = new AtomicInteger();
        var source = wrappedDatabase(null, true);
        var witnessed = SourceDatabaseWitness.capture(source, checks::incrementAndGet);
        assertThat(witnessed).isEqualTo(new SourceDatabaseMetadata(1, SourceDatabaseFamily.HSQLDB));
        assertThat(checks.get()).isGreaterThanOrEqualTo(2);
        assertThat(com.taxonomy.exchange.backup.PortableRows.json().writeValueAsString(witnessed))
                .isEqualTo("{\"schemaVersion\":1,\"family\":\"HSQLDB\"}");
    }
    @Test void unknownVendorCannotBecomeAPromisedMatrixCombination() throws Exception {
        assertThatThrownBy(() -> SourceDatabaseWitness.capture(wrappedDatabase("PRIVATE-UNSUPPORTED-VENDOR", true), () -> {}))
                .isInstanceOf(IOException.class).hasNoCause().hasMessageNotContaining("PRIVATE");
        for (String unsupported : new String[]{"", "SQLite", "PostgreSQL PRIVATE"})
            assertThatThrownBy(() -> SourceDatabaseFamily.fromProductName(unsupported)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void exactKnownVendorsAndSchemaAreClosedContracts() {
        assertThat(SourceDatabaseFamily.fromProductName("PostgreSQL")).isEqualTo(SourceDatabaseFamily.POSTGRESQL);
        assertThat(SourceDatabaseFamily.fromProductName("Microsoft SQL Server")).isEqualTo(SourceDatabaseFamily.SQL_SERVER);
        assertThat(SourceDatabaseFamily.fromProductName("Oracle")).isEqualTo(SourceDatabaseFamily.ORACLE);
        assertThatThrownBy(() -> new SourceDatabaseMetadata(2, SourceDatabaseFamily.HSQLDB)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cancellationPreservesInterruptAndSanitizesMetadataSourceDetails() throws Exception {
        var checks = new AtomicInteger();
        try {
            assertThatThrownBy(() -> SourceDatabaseWitness.capture(wrappedDatabase(null, true),
                    () -> { if (checks.incrementAndGet() == 2) throw new InterruptedIOException("PRIVATE-CANCEL"); }))
                    .isInstanceOf(InterruptedIOException.class).hasNoCause().hasMessageNotContaining("PRIVATE");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
    private static DelegatingDataSource wrappedDatabase(String product, boolean forbidPrivateFields) {
        var database = new JDBCDataSource(); database.setURL("jdbc:hsqldb:mem:witness-" + UUID.randomUUID()); database.setUser("sa");
        return new DelegatingDataSource(database) {
            @Override public Connection getConnection() throws SQLException {
                var actual = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (ignored, method, args) -> {
                    if (method.getName().equals("getMetaData")) {
                        var metadata = actual.getMetaData();
                        return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (unused, field, parameters) -> {
                            if (forbidPrivateFields && (field.getName().equals("getURL") || field.getName().equals("getUserName"))) throw new AssertionError("Private metadata must not be captured");
                            if (product != null && field.getName().equals("getDatabaseProductName")) return product;
                            return invoke(metadata, field, parameters);
                        });
                    }
                    return invoke(actual, method, args);
                });
            }
        };
    }
    private static Object invoke(Object object, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(object, arguments); } catch (InvocationTargetException problem) { throw problem.getCause(); }
    }
}
