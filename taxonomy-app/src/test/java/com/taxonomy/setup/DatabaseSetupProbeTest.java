package com.taxonomy.setup;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseSetupProbeTest {
    @Test void probeTimeoutsUseDriverNamesAndUnitsWithoutSpawningPgJdbcLoginWorkers() throws Exception {
        var postgres = SetupProbe.connectionProperties("jdbc:postgresql://db/taxonomy", "user", "PRIVATE_SENTINEL");
        assertEquals(5, org.postgresql.PGProperty.CONNECT_TIMEOUT.getInt(postgres));
        assertEquals(5, org.postgresql.PGProperty.SOCKET_TIMEOUT.getInt(postgres));
        assertEquals(0, org.postgresql.PGProperty.LOGIN_TIMEOUT.getInt(postgres));
        var mssql = SetupProbe.connectionProperties("jdbc:sqlserver://db;databaseName=taxonomy", "user", "PRIVATE_SENTINEL");
        assertEquals("5", mssql.getProperty("loginTimeout")); // Microsoft driver: seconds.
        assertEquals("5000", mssql.getProperty("socketTimeout")); // Microsoft driver: milliseconds.
        var oracleProperties = SetupProbe.connectionProperties("jdbc:oracle:thin:@db:1521/taxonomy", "user", "PRIVATE_SENTINEL");
        assertEquals("5000", oracleProperties.getProperty(oracle.jdbc.OracleConnection.CONNECTION_PROPERTY_THIN_NET_CONNECT_TIMEOUT));
        assertEquals("5000", oracleProperties.getProperty(oracle.jdbc.OracleConnection.CONNECTION_PROPERTY_THIN_READ_TIMEOUT));
        assertEquals("5sec", oracleProperties.getProperty(oracle.jdbc.OracleConnection.CONNECTION_PROPERTY_THIN_OUTBOUND_CONNECT_TIMEOUT));
        assertEquals("PRIVATE_SENTINEL", oracleProperties.getProperty("password"));
    }

    @Test void separateConnectionIsReadOnlyClosedAndFailuresNeverExposeDriverMessages() throws Exception {
        var readOnly = new AtomicBoolean(); var closed = new AtomicBoolean();
        Driver driver = driver((url, properties) -> {
            assertEquals("PRIVATE_SENTINEL", properties.getProperty("password"));
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setReadOnly" -> { readOnly.set((Boolean) args[0]); return null; }
                    case "isValid" -> { assertEquals(5, args[0]); return true; }
                    case "close" -> { closed.set(true); return null; }
                    default -> throw new AssertionError("Unexpected JDBC operation: " + method.getName());
                }
            });
        });
        DriverManager.registerDriver(driver);
        try {
            var finding = SetupProbe.checkDatabase(properties());
            assertEquals(SetupChecks.Status.OK, finding.status());
            assertTrue(readOnly.get()); assertTrue(closed.get());
        } finally { DriverManager.deregisterDriver(driver); }
        Driver failed = driver((url, properties) -> { throw new SQLException("password=PRIVATE_SENTINEL"); });
        DriverManager.registerDriver(failed);
        try {
            var finding = SetupProbe.checkDatabase(properties());
            assertEquals(SetupChecks.Status.ERROR, finding.status());
            assertFalse(finding.toString().contains("PRIVATE_SENTINEL"));
        } finally { DriverManager.deregisterDriver(failed); }
    }

    @Test void concurrentConnectionAttemptsHaveABoundedGlobalLimit() throws Exception {
        CountDownLatch connected = new CountDownLatch(2), release = new CountDownLatch(1);
        Driver driver = driver((url, properties) -> {
            connected.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            throw new SQLException("PRIVATE_SENTINEL");
        });
        DriverManager.registerDriver(driver);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> SetupProbe.checkDatabase(properties()));
            var second = workers.submit(() -> SetupProbe.checkDatabase(properties()));
            try {
                assertTrue(connected.await(3, TimeUnit.SECONDS), "Expected two separate bounded probes");
                var busy = SetupProbe.checkDatabase(properties());
                assertEquals(SetupChecks.Status.WARNING, busy.status());
                assertTrue(busy.message().contains("still running"));
            } finally { release.countDown(); }
            assertEquals(SetupChecks.Status.ERROR, first.get(3, TimeUnit.SECONDS).status());
            assertEquals(SetupChecks.Status.ERROR, second.get(3, TimeUnit.SECONDS).status());
        } finally { release.countDown(); DriverManager.deregisterDriver(driver); }
    }

    private static java.util.function.Function<String, String> properties() {
        return Map.of("spring.datasource.url", "jdbc:taxonomy-setup-test:db", "spring.datasource.username", "user", "spring.datasource.password", "PRIVATE_SENTINEL")::get;
    }
    @FunctionalInterface private interface Connect { Connection open(String url, Properties properties) throws SQLException; }
    private static Driver driver(Connect connection) {
        return new Driver() {
            public Connection connect(String url, Properties properties) throws SQLException { return acceptsURL(url) ? connection.open(url, properties) : null; }
            public boolean acceptsURL(String url) { return url.startsWith("jdbc:taxonomy-setup-test:"); }
            public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
            public int getMajorVersion() { return 1; }
            public int getMinorVersion() { return 0; }
            public boolean jdbcCompliant() { return false; }
            public Logger getParentLogger() { return Logger.getAnonymousLogger(); }
        };
    }
}
