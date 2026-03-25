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
 * Tagged with {@code db-oracle} — included in the default {@code mvn verify}
 * run (requires Docker). Execute explicitly with:
 * <pre>
 * mvn verify -DexcludedGroups=real-llm -Dit.test=DiagnosticsOracleContainerIT
 * </pre>
 */
@Testcontainers
@Tag("db-oracle")
class DiagnosticsOracleContainerIT extends AbstractDatabaseContainerIT {

    static Network network = Network.newNetwork();

    @Container
    static OracleContainer db = ContainerTestUtils.oracleContainer(network);

    @Container
    static GenericContainer<?> app = ContainerTestUtils.oracleAppContainer(network)
            .dependsOn(db);

    @Override
    protected GenericContainer<?> getAppContainer() {
        return app;
    }
}
