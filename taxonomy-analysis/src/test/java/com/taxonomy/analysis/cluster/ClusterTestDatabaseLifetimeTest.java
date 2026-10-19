package com.taxonomy.analysis.cluster;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTestDatabaseLifetimeTest {
    @Test void closingAnOwnedFixtureReleasesItsMemoryDatabase() throws Exception {
        String url;
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            url = ((DriverManagerDataSource) db.bean.getDataSource()).getUrl();
            assertNotNull(url);
            assertEquals(0L, db.em.createQuery("select count(r) from ClusterAnalysisRun r", Long.class)
                    .getSingleResult());
        }
        assertThrows(SQLException.class, () -> {
            try (var ignored = DriverManager.getConnection(url + ";ifexists=true", "sa", "")) {
                fail("An owned test database must be shut down when its fixture closes");
            }
        });
    }

    @Test void anExternallyOwnedDatabaseSurvivesFactoryClosureForRestartChecks() throws Exception {
        String url = "jdbc:hsqldb:mem:fixture-restart-" + UUID.randomUUID();
        try {
            try (var db = new ClusterAnalysisStoreTest.Database(url, "create", sql -> sql)) {
                assertEquals(0L, db.em.createQuery("select count(r) from ClusterAnalysisRun r", Long.class)
                        .getSingleResult());
            }
            try (var db = new ClusterAnalysisStoreTest.Database(url, "validate", sql -> sql)) {
                assertEquals(0L, db.em.createQuery("select count(r) from ClusterAnalysisRun r", Long.class)
                        .getSingleResult());
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, "sa", "");
                 var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
    }
}
