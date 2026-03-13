package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Selenium UI + REST tests against <strong>PostgreSQL</strong>.
 * <p>
 * Tagged with {@code db-postgres} — excluded from the default {@code mvn verify}.
 * Execute explicitly with:
 * <pre>
 * mvn verify -DexcludedGroups=real-llm -Dit.test=SeleniumPostgresContainerIT
 * </pre>
 */
@Testcontainers
@Tag("db-postgres")
class SeleniumPostgresContainerIT extends AbstractSeleniumContainerIT {

    @Override
    protected GenericContainer<?> createDbContainer(Network net) {
        return new PostgreSQLContainer("postgres:16-alpine")
                .withNetwork(net)
                .withNetworkAliases("db")
                .withDatabaseName("taxonomy")
                .withUsername("taxonomy")
                .withPassword("taxonomy");
    }

    @Override
    protected GenericContainer<?> createAppContainer(Network net) {
        return ContainerTestUtils.appContainer(net)
                .withEnv("TAXONOMY_DATASOURCE_URL", "jdbc:postgresql://db:5432/taxonomy")
                .withEnv("SPRING_DATASOURCE_DRIVER_CLASS_NAME", "org.postgresql.Driver")
                .withEnv("SPRING_JPA_DATABASE_PLATFORM", "org.hibernate.dialect.PostgreSQLDialect")
                .withEnv("SPRING_DATASOURCE_USERNAME", "taxonomy")
                .withEnv("SPRING_DATASOURCE_PASSWORD", "taxonomy")
                .withEnv("SPRING_DATASOURCE_TYPE", "com.zaxxer.hikari.HikariDataSource")
                .withEnv("TAXONOMY_DDL_AUTO", "create");
    }
}
