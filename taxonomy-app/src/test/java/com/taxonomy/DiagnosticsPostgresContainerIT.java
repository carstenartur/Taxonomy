package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:16-alpine")
            .withNetwork(network)
            .withNetworkAliases("db")
            .withDatabaseName("taxonomy")
            .withUsername("taxonomy")
            .withPassword("taxonomy");

    @Container
    static GenericContainer<?> app = ContainerTestUtils.appContainer(network)
            .withEnv("SPRING_PROFILES_ACTIVE", "postgres")
            .withEnv("TAXONOMY_DATASOURCE_URL", "jdbc:postgresql://db:5432/taxonomy")
            .withEnv("SPRING_DATASOURCE_USERNAME", "taxonomy")
            .withEnv("SPRING_DATASOURCE_PASSWORD", "taxonomy")
            .withEnv("TAXONOMY_DDL_AUTO", "create")
            .dependsOn(db);

    @Override
    protected GenericContainer<?> getAppContainer() {
        return app;
    }
}
