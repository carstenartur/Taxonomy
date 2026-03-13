package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

/**
 * Runs the same diagnostics + API tests as {@link DiagnosticsContainerIT}
 * but against a <strong>Microsoft SQL Server</strong> database backend.
 * <p>
 * Tagged with {@code db-mssql} — excluded from the default {@code mvn verify}
 * run. Execute explicitly with:
 * <pre>
 * mvn verify -DexcludedGroups=real-llm -Dit.test=DiagnosticsMssqlContainerIT
 * </pre>
 */
@Testcontainers
@Tag("db-mssql")
class DiagnosticsMssqlContainerIT extends AbstractDatabaseContainerIT {

    static Network network = Network.newNetwork();

    private static final String MSSQL_PASSWORD = "A_Str0ng_Required_Password";

    @Container
    static MSSQLServerContainer db = new MSSQLServerContainer(
            "mcr.microsoft.com/mssql/server:2022-latest")
            .withNetwork(network)
            .withNetworkAliases("db")
            .withPassword(MSSQL_PASSWORD)
            .acceptLicense();

    @Container
    static GenericContainer<?> app = ContainerTestUtils.appContainer(network)
            .withEnv("TAXONOMY_DATASOURCE_URL",
                    "jdbc:sqlserver://db:1433;databaseName=master;encrypt=false;trustServerCertificate=true")
            .withEnv("SPRING_DATASOURCE_DRIVER_CLASS_NAME",
                    "com.microsoft.sqlserver.jdbc.SQLServerDriver")
            .withEnv("SPRING_JPA_DATABASE_PLATFORM",
                    "org.hibernate.dialect.SQLServerDialect")
            .withEnv("SPRING_DATASOURCE_USERNAME", "sa")
            .withEnv("SPRING_DATASOURCE_PASSWORD", MSSQL_PASSWORD)
            .withEnv("SPRING_DATASOURCE_TYPE", "com.zaxxer.hikari.HikariDataSource")
            .withEnv("TAXONOMY_DDL_AUTO", "create")
            .dependsOn(db);

    @Override
    protected GenericContainer<?> getAppContainer() {
        return app;
    }
}
