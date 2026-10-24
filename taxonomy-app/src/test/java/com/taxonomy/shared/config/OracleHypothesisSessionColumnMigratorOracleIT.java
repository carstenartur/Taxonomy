package com.taxonomy.shared.config;

import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import javax.sql.DataSource;
import java.time.Duration;

/** Historical upgrade/recovery owner in {@code -Pdatabase-oracle}, requires Docker. */
@Testcontainers
@Tag("db-oracle")
class OracleHypothesisSessionColumnMigratorOracleIT extends OracleHypothesisSessionMigrationContract {
    // Same database image and credentials as ContainerTestUtils.oracleContainer.
    @Container
    static final OracleContainer DATABASE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withDatabaseName("taxonomy")
            .withUsername("taxonomy")
            .withPassword("taxonomy")
            .withStartupTimeout(Duration.ofMinutes(3));

    @org.junit.jupiter.api.Test
    void retainedHypothesisBranchAuthority() throws Exception {
        HypothesisBranchMigrationContract.verify(database());
    }

    @Override
    DataSource database() {
        return new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    }
}
