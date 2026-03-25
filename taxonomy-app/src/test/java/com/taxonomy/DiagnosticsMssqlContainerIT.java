package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the same diagnostics + API tests as {@link DiagnosticsContainerIT}
 * but against a <strong>Microsoft SQL Server</strong> database backend.
 * <p>
 * Tagged with {@code db-mssql} — included in the default {@code mvn verify}
 * run (requires Docker). Execute explicitly with:
 * <pre>
 * mvn verify -DexcludedGroups=real-llm -Dit.test=DiagnosticsMssqlContainerIT
 * </pre>
 */
@Testcontainers
@Tag("db-mssql")
class DiagnosticsMssqlContainerIT extends AbstractDatabaseContainerIT {

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
}
