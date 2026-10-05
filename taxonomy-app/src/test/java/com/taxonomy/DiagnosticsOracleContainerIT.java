package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import java.time.Duration;

/**
 * Runs diagnostics, API and durable cluster execution contracts
 * but against an <strong>Oracle Database Free</strong> backend.
 * <p>
 * Included in the Maven-owned Oracle compatibility lane (requires Docker):
 * <pre>
 * ./mvnw -B verify -Pdatabase-oracle
 * </pre>
 */
@Testcontainers
@Tag("db-oracle")
class DiagnosticsOracleContainerIT extends AbstractExternalDatabaseContainerIT {

    static Network network = Network.newNetwork();

    @Container
    static OracleContainer db = ContainerTestUtils.oracleContainer(network)
            .withStartupTimeout(Duration.ofMinutes(3));

    @Container
    static GenericContainer<?> app = ContainerTestUtils.oracleAppContainer(network)
            .dependsOn(db);

    @Override
    protected GenericContainer<?> getAppContainer() {
        return app;
    }

    @Override
    protected org.testcontainers.containers.JdbcDatabaseContainer<?> getDatabaseContainer() {
        return db;
    }
}
