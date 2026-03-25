package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;

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
        return ContainerTestUtils.oracleContainer(net);
    }

    @Override
    protected GenericContainer<?> createAppContainer(Network net) {
        return ContainerTestUtils.oracleAppContainer(net);
    }
}
