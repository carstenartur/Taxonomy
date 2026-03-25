package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the same diagnostics + API tests as {@link DiagnosticsContainerIT}
 * but against a <strong>PostgreSQL</strong> database backend.
 * <p>
 * Tagged with {@code db-postgres} — included in the default {@code mvn verify}
 * run (requires Docker). Execute explicitly with:
 * <pre>
 * mvn verify -DexcludedGroups=real-llm -Dit.test=DiagnosticsPostgresContainerIT
 * </pre>
 */
@Testcontainers
@Tag("db-postgres")
class DiagnosticsPostgresContainerIT extends AbstractDatabaseContainerIT {

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
}
