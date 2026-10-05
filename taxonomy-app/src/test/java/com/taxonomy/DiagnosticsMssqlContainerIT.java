package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs diagnostics, API and durable cluster execution contracts
 * but against a <strong>Microsoft SQL Server</strong> database backend.
 * <p>
 * Included in the Maven-owned SQL Server compatibility lane (requires Docker):
 * <pre>
 * ./mvnw -B verify -Pdatabase-mssql
 * </pre>
 */
@Testcontainers
@Tag("db-mssql")
class DiagnosticsMssqlContainerIT extends AbstractExternalDatabaseContainerIT {

    static Network network = Network.newNetwork();

    @Container
    @SuppressWarnings("rawtypes")
    static org.testcontainers.mssqlserver.MSSQLServerContainer db =
            ContainerTestUtils.mssqlContainer(network);

    @Container
    static GenericContainer<?> app = ContainerTestUtils.mssqlAppContainer(network)
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
