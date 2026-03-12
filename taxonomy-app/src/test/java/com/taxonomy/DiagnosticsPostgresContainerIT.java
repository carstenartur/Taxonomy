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
 * Tagged with {@code external-db} — excluded from the default {@code mvn verify}
 * run. Execute explicitly with:
 * <pre>
 * mvn verify -DexcludedGroups= -Dit.test=DiagnosticsPostgresContainerIT
 * </pre>
 */
@Testcontainers
@Tag("external-db")
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
            .withEnv("TAXONOMY_DATASOURCE_URL", "jdbc:postgresql://db:5432/taxonomy")
            .withEnv("SPRING_DATASOURCE_DRIVER_CLASS_NAME", "org.postgresql.Driver")
            .withEnv("SPRING_JPA_DATABASE_PLATFORM", "org.hibernate.dialect.PostgreSQLDialect")
            .withEnv("SPRING_DATASOURCE_USERNAME", "taxonomy")
            .withEnv("SPRING_DATASOURCE_PASSWORD", "taxonomy")
            .withEnv("SPRING_DATASOURCE_TYPE", "com.zaxxer.hikari.HikariDataSource")
            .withEnv("TAXONOMY_DDL_AUTO", "create")
            .dependsOn(db);

    @Override
    protected GenericContainer<?> getAppContainer() {
        return app;
    }
}
