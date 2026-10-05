package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs diagnostics, API and durable cluster execution contracts
 * but against a <strong>PostgreSQL</strong> database backend.
 * <p>
 * Included in the Maven-owned PostgreSQL compatibility lane (requires Docker):
 * <pre>
 * ./mvnw -B verify -Pdatabase-postgres
 * </pre>
 */
@Testcontainers
@Tag("db-postgres")
class DiagnosticsPostgresContainerIT extends AbstractExternalDatabaseContainerIT {

    static Network network = Network.newNetwork();

    @Container
    @SuppressWarnings("rawtypes")
    static org.testcontainers.postgresql.PostgreSQLContainer db =
            ContainerTestUtils.postgresContainer(network);

    @Container
    static GenericContainer<?> app = ContainerTestUtils.postgresAppContainer(network)
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
