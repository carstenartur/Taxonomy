package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Selenium UI + REST tests against <strong>PostgreSQL</strong>.
 * <p>
 * Tagged with {@code db-postgres} — included in the default {@code mvn verify}
 * run (requires Docker). Execute explicitly with:
 * <pre>
 * mvn verify -DexcludedGroups=real-llm -Dit.test=SeleniumPostgresContainerIT
 * </pre>
 */
@Testcontainers
@Tag("db-postgres")
class SeleniumPostgresContainerIT extends AbstractSeleniumContainerIT {

    @Override
    protected GenericContainer<?> createDbContainer(Network net) {
        return ContainerTestUtils.postgresContainer(net);
    }

    @Override
    protected GenericContainer<?> createAppContainer(Network net) {
        return ContainerTestUtils.postgresAppContainer(net);
    }
}
