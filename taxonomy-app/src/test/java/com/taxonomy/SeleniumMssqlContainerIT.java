package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

/**
 * Selenium UI + REST tests against <strong>Microsoft SQL Server</strong>.
 * <p>
 * Tagged with {@code external-db} — excluded from the default {@code mvn verify}.
 * Execute explicitly with:
 * <pre>
 * mvn verify -DexcludedGroups= -Dit.test=SeleniumMssqlContainerIT
 * </pre>
 */
@Testcontainers
@Tag("external-db")
class SeleniumMssqlContainerIT extends AbstractSeleniumContainerIT {

    private static final String MSSQL_PASSWORD = "A_Str0ng_Required_Password";

    @Override
    protected GenericContainer<?> createDbContainer(Network net) {
        return new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
                .withNetwork(net)
                .withNetworkAliases("db")
                .withPassword(MSSQL_PASSWORD)
                .acceptLicense();
    }

    @Override
    protected GenericContainer<?> createAppContainer(Network net) {
        return ContainerTestUtils.appContainer(net)
                .withEnv("TAXONOMY_DATASOURCE_URL",
                        "jdbc:sqlserver://db:1433;databaseName=master;encrypt=false;trustServerCertificate=true")
                .withEnv("SPRING_DATASOURCE_DRIVER_CLASS_NAME",
                        "com.microsoft.sqlserver.jdbc.SQLServerDriver")
                .withEnv("SPRING_JPA_DATABASE_PLATFORM",
                        "org.hibernate.dialect.SQLServerDialect")
                .withEnv("SPRING_DATASOURCE_USERNAME", "sa")
                .withEnv("SPRING_DATASOURCE_PASSWORD", MSSQL_PASSWORD)
                .withEnv("SPRING_DATASOURCE_TYPE", "com.zaxxer.hikari.HikariDataSource")
                .withEnv("TAXONOMY_DDL_AUTO", "create");
    }
}
