package com.taxonomy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the exact external-database contract locally; this is not vendor acceptance. */
class ClusterAnalysisDatabaseContractTest {
    @Test void historyFixtureBoundsPhysicalConnectionsAndClosesThem() {
        var database = new ConnectionCounter();
        try (var contract = ClusterAnalysisDatabaseContract.hsql(database)) {
            contract.ownerSourceAndRequirementScopeIsAppliedBeforeTheHistoryLimit();
        }
        assertTrue(database.opened.get() <= 8,
                () -> "History fixture must reuse connections instead of flooding the listener; opened "
                        + database.opened.get());
        assertEquals(0, database.active.get(), "Closing the fixture must release every physical connection");
    }

    @Test void failedSchemaValidationClosesTheOwnedConnections() {
        var database = new ConnectionCounter();
        assertThrows(RuntimeException.class, () -> ClusterAnalysisDatabaseContract.pooledExisting(database));
        assertTrue(database.opened.get() > 0, "Schema validation must have reached the real database");
        assertEquals(0, database.active.get(), "Failed initialization must not leak a connection pool");
    }

    @Test void largePayloadsAndAllFourTablesSurviveANewPersistenceFactory() {
        try (var contract = ClusterAnalysisDatabaseContract.hsql()) {
            contract.largePayloadsAndAllFourTablesSurviveANewPersistenceFactory();
        }
    }
    @Test void concurrentDuplicateCompletionRollsBackAndSettlesExactlyOnce() throws Exception {
        try (var contract = ClusterAnalysisDatabaseContract.hsql()) {
            contract.concurrentDuplicateCompletionRollsBackAndSettlesExactlyOnce();
        }
    }
    @Test void bothDeliveriesReachTheJdbcInsertBarrierBeforeEitherExecutesItsInsert() throws Exception {
        var caller = Thread.currentThread();
        var inserts = new CopyOnWriteArrayList<ClusterAnalysisDatabaseContract.CompletionInsert>();
        try (var contract = ClusterAnalysisDatabaseContract.hsql()) {
            contract.observeCompletionInserts(event -> {
                // Initial rollback and the independent IP completion run on the calling thread.
                if (Thread.currentThread() != caller) inserts.add(event);
            });
            contract.concurrentDuplicateCompletionRollsBackAndSettlesExactlyOnce();
        }
        assertEquals(4, inserts.size(), "Both real JDBC inserts must reach the callback and execute boundary");
        assertFalse(inserts.get(0).executingSql());
        assertFalse(inserts.get(1).executingSql(),
                "Both delivery transactions must pass their absent-row reads before either inserts its reservation");
        assertTrue(inserts.get(2).executingSql());
        assertTrue(inserts.get(3).executingSql());
        assertNotSame(inserts.get(0).connection(), inserts.get(1).connection(),
                "The duplicate race must use two independent JDBC connections");
        assertEquals(Set.of(inserts.get(0).connection(), inserts.get(1).connection()),
                Set.of(inserts.get(2).connection(), inserts.get(3).connection()));
    }
    @Test void ownerSourceAndRequirementScopeIsAppliedBeforeTheHistoryLimit() {
        try (var contract = ClusterAnalysisDatabaseContract.hsql()) {
            contract.ownerSourceAndRequirementScopeIsAppliedBeforeTheHistoryLimit();
        }
    }

    private static final class ConnectionCounter extends DelegatingDataSource {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();

        ConnectionCounter() {
            super(new DriverManagerDataSource("jdbc:hsqldb:mem:connection-budget-"
                    + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", ""));
        }

        @Override public Connection getConnection() throws SQLException {
            return track(super.getConnection());
        }

        @Override public Connection getConnection(String username, String password) throws SQLException {
            return track(super.getConnection(username, password));
        }

        private Connection track(Connection connection) {
            opened.incrementAndGet();
            active.incrementAndGet();
            var closed = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(connection, arguments);
                            if (method.getName().equals("close") && closed.compareAndSet(false, true)) {
                                active.decrementAndGet();
                            }
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
