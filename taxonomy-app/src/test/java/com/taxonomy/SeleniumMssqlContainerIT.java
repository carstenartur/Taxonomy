package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Selenium UI + REST tests against <strong>Microsoft SQL Server</strong>.
 * <p>
 * Tagged with {@code db-mssql} — included in the default {@code mvn verify}
 * run (requires Docker). Execute explicitly with:
 * <pre>
 * mvn verify -DexcludedGroups=real-llm -Dit.test=SeleniumMssqlContainerIT
 * </pre>
 */
@Testcontainers
@Tag("db-mssql")
class SeleniumMssqlContainerIT extends AbstractSeleniumContainerIT {

    @Override
    protected GenericContainer<?> createDbContainer(Network net) {
        return ContainerTestUtils.mssqlContainer(net);
    }

    @Override
    protected GenericContainer<?> createAppContainer(Network net) {
        return ContainerTestUtils.mssqlAppContainer(net);
    }
}
