package com.taxonomy.setup;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import javax.net.ssl.SSLHandshakeException;
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

    // These fixtures catch loss of semantic classification or accidental exception-message disclosure.
    @Test void jdbcFailuresHaveSpecificSafeActionableDiagnostics() throws Exception {
        record Failure(SQLException exception, String key) { }
        for (var failure : List.of(
                new Failure(new SQLException("PRIVATE_SENTINEL", "08001", new UnknownHostException("DNS_PRIVATE_SENTINEL")), "dns"),
                new Failure(new SQLException("PRIVATE_SENTINEL", "08001", new ConnectException("REFUSED_PRIVATE_SENTINEL")), "refused"),
                new Failure(new SQLException("PRIVATE_SENTINEL", "08001", new SocketTimeoutException("TIMEOUT_PRIVATE_SENTINEL")), "timeout"),
                new Failure(new SQLTimeoutException("TIMEOUT_PRIVATE_SENTINEL"), "timeout"),
                new Failure(new SQLException("PRIVATE_SENTINEL", "HYT00"), "timeout"),
                new Failure(new SQLException("PRIVATE_SENTINEL", "08006", new SSLHandshakeException("TLS_PRIVATE_SENTINEL")), "tls"),
                new Failure(new SQLException("PRIVATE_SENTINEL", "08006", new java.security.cert.CertificateException("CERT_PRIVATE_SENTINEL")), "tls"),
                new Failure(new SQLException("PRIVATE_SENTINEL", "28P01"), "authentication"),
                new Failure(new SQLException("PRIVATE_SENTINEL", "28000"), "authentication"),
                new Failure(new SQLException("PRIVATE_SENTINEL", "3D000"), "missing-database"),
                new Failure(new SQLException("PRIVATE_SENTINEL", "42501"), "permission"),
                new Failure(new SQLException("PRIVATE_SENTINEL", "08001", new InterruptedException("INTERRUPTED_PRIVATE_SENTINEL")), "interrupted"))) {
            Driver failed = driver((url, properties) -> { throw failure.exception(); });
            DriverManager.registerDriver(failed);
            try {
                var finding = SetupProbe.checkDatabase(properties());
                assertEquals(SetupChecks.Status.ERROR, finding.status());
                assertEquals("database.connection." + failure.key(), finding.key());
                assertFalse(finding.toString().contains("PRIVATE_SENTINEL"));
                assertFalse(finding.message().isBlank());
            } finally { DriverManager.deregisterDriver(failed); }
        }
    }

    @Test void postgresServerRejectingSslRequestHasTlsDiagnosisWithoutDowngrading() throws Exception {
        Class.forName("org.postgresql.Driver");
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(5000);
            var exchange = workers.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    assertArrayEquals(new byte[]{0, 0, 0, 8, 4, (byte) 210, 22, 47}, socket.getInputStream().readNBytes(8));
                    socket.getOutputStream().write('N'); // PostgreSQL SSLRequest: this server has no TLS support.
                    socket.getOutputStream().flush();
                    assertEquals(-1, socket.getInputStream().read(), "The client must close instead of sending a plaintext login");
                }
                return null;
            });
            var settings = Map.of("spring.datasource.url", "jdbc:postgresql://127.0.0.1:" + server.getLocalPort()
                            + "/taxonomy?sslmode=verify-full&gssEncMode=disable", "spring.datasource.username", "user",
                    "spring.datasource.password", "PRIVATE_SENTINEL");
            var finding = SetupProbe.checkDatabase(settings::get);
            assertEquals("database.connection.tls", finding.key());
            assertFalse(finding.toString().contains("PRIVATE_SENTINEL"));
            exchange.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void connectionRejectedSqlStateAloneDoesNotImplyTlsFailure() {
        var rejected = SetupProbe.databaseFailure(new SQLException("PRIVATE_SENTINEL", "08004"));
        assertEquals("database.connection", rejected.key());
        var unrelatedDriver = SetupProbe.databaseFailure(new SQLException("The server does not support SSL.", "08004"));
        assertEquals("database.connection", unrelatedDriver.key());
        var otherPostgresRejection = SetupProbe.databaseFailure(new org.postgresql.util.PSQLException(
                "PRIVATE_SENTINEL TLS unsupported", org.postgresql.util.PSQLState.CONNECTION_REJECTED));
        assertEquals("database.connection", otherPostgresRejection.key());
        assertFalse(otherPostgresRejection.toString().contains("PRIVATE_SENTINEL"));
    }

    @Test void unknownFailureDoesNotGuessFromSecretBearingMessage() throws Exception {
        Driver failed = driver((url, properties) -> {
            throw new SQLException("password=PRIVATE_SENTINEL DNS refused TLS authentication timeout", "XX000");
        });
        DriverManager.registerDriver(failed);
        try {
            var finding = SetupProbe.checkDatabase(properties());
            assertEquals("database.connection", finding.key());
            assertTrue(finding.message().contains("unknown"));
            assertFalse(finding.toString().contains("PRIVATE_SENTINEL"));
        } finally { DriverManager.deregisterDriver(failed); }
    }

    @Test void chainedSqlExceptionsAreClassifiedWithoutLoopingOverCyclicCauses() throws Exception {
        var outer = new SQLException("OUTER_PRIVATE_SENTINEL", "08001");
        var next = new SQLException("NEXT_PRIVATE_SENTINEL", "3D000");
        outer.initCause(next);
        next.initCause(outer);
        outer.setNextException(next);
        Driver failed = driver((url, properties) -> { throw outer; });
        DriverManager.registerDriver(failed);
        try {
            var finding = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> SetupProbe.checkDatabase(properties()));
            assertEquals("database.connection.missing-database", finding.key());
            assertFalse(finding.toString().contains("PRIVATE_SENTINEL"));
        } finally { DriverManager.deregisterDriver(failed); }
    }

    @Test void sqlNextExceptionWithoutCauseProvidesSafeDiagnosis() {
        var failure = new SQLException("OUTER_PRIVATE_SENTINEL", "08001");
        failure.setNextException(new SQLException("NEXT_PRIVATE_SENTINEL", "42501"));
        var finding = SetupProbe.databaseFailure(failure);
        assertEquals("database.connection.permission", finding.key());
        assertFalse(finding.toString().contains("PRIVATE_SENTINEL"));
    }

    @Test void cyclicAndExcessivelyDeepUnknownFailuresRemainBoundedAndRedacted() {
        var first = new SQLException("FIRST_PRIVATE_SENTINEL", "08001");
        var second = new SQLException("SECOND_PRIVATE_SENTINEL", "08001");
        first.initCause(second); second.initCause(first);
        var cyclic = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> SetupProbe.databaseFailure(first));
        assertEquals("database.connection", cyclic.key());
        assertFalse(cyclic.toString().contains("PRIVATE_SENTINEL"));
        Throwable deep = new SQLException("TAIL_PRIVATE_SENTINEL", "3D000");
        for (int i = 0; i < 1000; i++) { deep = new Exception("WRAPPER_PRIVATE_SENTINEL", deep); }
        Throwable failure = deep;
        var bounded = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> SetupProbe.databaseFailure(failure));
        assertEquals("database.connection", bounded.key());
        assertFalse(bounded.toString().contains("PRIVATE_SENTINEL"));
    }

    @Test void successfulRetryReplacesEarlierAuthenticationFailure() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var closed = new AtomicBoolean();
        Driver retry = driver((url, properties) -> {
            if (attempts.getAndIncrement() == 0) { throw new SQLException("PRIVATE_SENTINEL", "28P01"); }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "setReadOnly" -> { assertEquals(true, args[0]); yield null; }
                    case "isValid" -> true;
                    case "close" -> { closed.set(true); yield null; }
                    default -> throw new AssertionError("Unexpected JDBC operation: " + method.getName());
                };
            });
        });
        DriverManager.registerDriver(retry);
        try {
            assertEquals("database.connection.authentication", SetupProbe.checkDatabase(properties()).key());
            var success = SetupProbe.checkDatabase(properties());
            assertEquals(SetupChecks.Status.OK, success.status());
            assertEquals("database.connection", success.key());
            assertTrue(closed.get());
            assertFalse(success.toString().contains("PRIVATE_SENTINEL"));
        } finally { DriverManager.deregisterDriver(retry); }
    }

    @Test void elapsedDatabaseDeadlineHasSpecificTimeoutResult() {
        var finding = SetupProbe.bounded("database.connection", () -> {
            Thread.sleep(10_000);
            return null;
        }, Duration.ofMillis(10));
        assertEquals(SetupChecks.Status.ERROR, finding.status());
        assertEquals("database.connection.timeout", finding.key());
    }

    @Test void interruptedCallerRetainsProbePermitUntilJdbcActuallyExits() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var jdbcWorkers = new ConcurrentLinkedQueue<Thread>();
        Driver blocking = driver((url, properties) -> {
            jdbcWorkers.add(Thread.currentThread());
            entered.countDown();
            boolean finished = false;
            while (!finished) {
                try { release.await(); finished = true; }
                catch (InterruptedException ignored) { /* This fixture models drivers ignoring cancellation. */ }
            }
            throw new SQLException("PRIVATE_SENTINEL");
        });
        var first = new java.util.concurrent.atomic.AtomicReference<SetupChecks.Finding>();
        var second = new java.util.concurrent.atomic.AtomicReference<SetupChecks.Finding>();
        var preservedInterrupt = new AtomicBoolean();
        DriverManager.registerDriver(blocking);
        Thread one = Thread.ofVirtual().unstarted(() -> {
            first.set(SetupProbe.checkDatabase(properties()));
            preservedInterrupt.set(Thread.currentThread().isInterrupted());
        });
        Thread two = Thread.ofVirtual().unstarted(() -> second.set(SetupProbe.checkDatabase(properties())));
        try {
            one.start(); two.start();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            one.interrupt(); two.interrupt();
            one.join(3000); two.join(3000);
            assertNotNull(first.get()); assertNotNull(second.get());
            assertEquals("database.connection.interrupted", first.get().key());
            assertEquals("database.connection.interrupted", second.get().key());
            assertTrue(preservedInterrupt.get());
            assertEquals(SetupChecks.Status.WARNING, SetupProbe.checkDatabase(properties()).status());
        } finally {
            release.countDown();
            one.interrupt(); two.interrupt();
            try {
                one.join(3000); two.join(3000);
                // The interrupted callers return before drivers that ignore cancellation exit.
                // Wait for the real workers so their permits cannot leak into the next test.
                for (Thread worker : jdbcWorkers) {
                    worker.join(5000);
                    assertFalse(worker.isAlive(), "JDBC worker must finish before the next test");
                }
            } finally { DriverManager.deregisterDriver(blocking); }
        }
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
