package com.taxonomy;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

/** Adds durable execution acceptance to the existing PostgreSQL, Oracle and SQL Server suites. */
abstract class AbstractExternalDatabaseContainerIT extends AbstractDatabaseContainerIT {
    protected abstract JdbcDatabaseContainer<?> getDatabaseContainer();

    private ClusterAnalysisDatabaseContract clusterContract() {
        var database = getDatabaseContainer();
        // Validate the running application's schema; never create/drop tables in its database.
        return ClusterAnalysisDatabaseContract.existing(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
    }

    @Test @Order(21)
    void clusterLargePayloadsAndAllFourTablesSurviveANewPersistenceFactory() {
        try (var contract = clusterContract()) {
            contract.largePayloadsAndAllFourTablesSurviveANewPersistenceFactory();
        }
    }
    @Test @Order(22)
    void clusterConcurrentDuplicateCompletionRollsBackAndSettlesExactlyOnce() throws Exception {
        try (var contract = clusterContract()) {
            contract.concurrentDuplicateCompletionRollsBackAndSettlesExactlyOnce();
        }
    }
    @Test @Order(23)
    void clusterOwnerSourceAndRequirementScopeIsAppliedBeforeTheHistoryLimit() {
        try (var contract = clusterContract()) {
            contract.ownerSourceAndRequirementScopeIsAppliedBeforeTheHistoryLimit();
        }
    }
}
