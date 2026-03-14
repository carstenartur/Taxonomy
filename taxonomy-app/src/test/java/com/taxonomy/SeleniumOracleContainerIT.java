package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * Selenium UI + REST tests against <strong>Oracle Database Free</strong>.
 * <p>
 * Tagged with {@code db-oracle} — included in the default {@code mvn verify}
 * run (requires Docker). Execute explicitly with:
 * <pre>
 * mvn verify -DexcludedGroups=real-llm -Dit.test=SeleniumOracleContainerIT
 * </pre>
 */
@Testcontainers
@Tag("db-oracle")
class SeleniumOracleContainerIT extends AbstractSeleniumContainerIT {

    @Override
    protected GenericContainer<?> createDbContainer(Network net) {
        return new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                .withNetwork(net)
                .withNetworkAliases("db")
                .withDatabaseName("taxonomy")
                .withUsername("taxonomy")
                .withPassword("taxonomy");
    }

    @Override
    protected GenericContainer<?> createAppContainer(Network net) {
        return ContainerTestUtils.appContainer(net)
                .withEnv("SPRING_PROFILES_ACTIVE", "oracle")
                .withEnv("TAXONOMY_DATASOURCE_URL",
                        "jdbc:oracle:thin:@db:1521/taxonomy")
                .withEnv("SPRING_DATASOURCE_USERNAME", "taxonomy")
                .withEnv("SPRING_DATASOURCE_PASSWORD", "taxonomy")
                .withEnv("TAXONOMY_DDL_AUTO", "create");
    }
}
