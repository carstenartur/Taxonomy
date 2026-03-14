package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

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

    private static final String MSSQL_PASSWORD = "A_Str0ng_Required_Password";

    @Override
    protected GenericContainer<?> createDbContainer(Network net) {
        return new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU18-ubuntu-22.04")
                .withNetwork(net)
                .withNetworkAliases("db")
                .withPassword(MSSQL_PASSWORD)
                .acceptLicense();
    }

    @Override
    protected GenericContainer<?> createAppContainer(Network net) {
        return ContainerTestUtils.appContainer(net)
                .withEnv("SPRING_PROFILES_ACTIVE", "mssql")
                .withEnv("TAXONOMY_DATASOURCE_URL",
                        "jdbc:sqlserver://db:1433;databaseName=master;encrypt=false;trustServerCertificate=true;loginTimeout=30")
                .withEnv("SPRING_DATASOURCE_USERNAME", "sa")
                .withEnv("SPRING_DATASOURCE_PASSWORD", MSSQL_PASSWORD)
                .withEnv("TAXONOMY_DDL_AUTO", "create");
    }
}
