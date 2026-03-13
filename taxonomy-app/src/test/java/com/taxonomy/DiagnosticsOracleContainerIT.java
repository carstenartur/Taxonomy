package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * Runs the same diagnostics + API tests as {@link DiagnosticsContainerIT}
 * but against an <strong>Oracle Database Free</strong> backend.
 * <p>
 * Tagged with {@code db-oracle} — excluded from the default {@code mvn verify}
 * run. Execute explicitly with:
 * <pre>
 * mvn verify -DexcludedGroups=real-llm -Dit.test=DiagnosticsOracleContainerIT
 * </pre>
 */
@Testcontainers
@Tag("db-oracle")
class DiagnosticsOracleContainerIT extends AbstractDatabaseContainerIT {

    static Network network = Network.newNetwork();

    @Container
    static OracleContainer db = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withNetwork(network)
            .withNetworkAliases("db")
            .withDatabaseName("taxonomy")
            .withUsername("taxonomy")
            .withPassword("taxonomy");

    @Container
    static GenericContainer<?> app = ContainerTestUtils.appContainer(network)
            .withEnv("TAXONOMY_DATASOURCE_URL",
                    "jdbc:oracle:thin:@db:1521/taxonomy")
            .withEnv("SPRING_DATASOURCE_DRIVER_CLASS_NAME",
                    "oracle.jdbc.OracleDriver")
            .withEnv("SPRING_JPA_DATABASE_PLATFORM",
                    "org.hibernate.dialect.OracleDialect")
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
