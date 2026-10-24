package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs diagnostics, API and durable cluster execution contracts
 * but against a <strong>Microsoft SQL Server</strong> database backend.
 * <p>
 * Included in the Maven-owned SQL Server compatibility lane (requires Docker):
 * <pre>
 * ./mvnw -B verify -Pdatabase-mssql
 * </pre>
 */
@Testcontainers
@Tag("db-mssql")
class DiagnosticsMssqlContainerIT extends AbstractExternalDatabaseContainerIT {

    static Network network = Network.newNetwork();

    @Container
    @SuppressWarnings("rawtypes")
    static org.testcontainers.mssqlserver.MSSQLServerContainer db =
            ContainerTestUtils.mssqlContainer(network);

    @Container
    static GenericContainer<?> app = ContainerTestUtils.mssqlAppContainer(network)
            .dependsOn(db);

    @org.junit.jupiter.api.Test
    void retainedHypothesisBranchAuthority() throws Exception {
        // A separate database keeps this retained-schema fixture away from the running app.
        var admin = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                db.getJdbcUrl(), db.getUsername(), db.getPassword());
        try (var connection=admin.getConnection(); var sql=connection.createStatement()) {
            // The app started from an empty database: Hibernate created these columns
            // and dependent keys before the portable runner established binary authority.
            try(var columns=sql.executeQuery("SELECT TABLE_NAME,COLLATION_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME IN ('relation_hypothesis','relation_evidence') AND COLUMN_NAME='branch_name'")) {
                int found=0;
                while(columns.next()) {
                    org.assertj.core.api.Assertions.assertThat(columns.getString(2)).isEqualTo("Latin1_General_100_BIN2");
                    found++;
                }
                org.assertj.core.api.Assertions.assertThat(found).isEqualTo(2);
            }
            sql.execute("CREATE DATABASE hypothesis_branch_contract");
        }
        String url=db.getJdbcUrl().replaceAll("(?i);databaseName=[^;]*", "")
                + ";databaseName=hypothesis_branch_contract";
        var isolated=new org.springframework.jdbc.datasource.DriverManagerDataSource(url,db.getUsername(),db.getPassword());
        try { com.taxonomy.shared.config.HypothesisBranchMigrationContract.verify(isolated); }
        finally {
            try(var connection=admin.getConnection(); var sql=connection.createStatement()) {
                sql.execute("DROP DATABASE hypothesis_branch_contract");
            }
        }
    }

    @Override
    protected GenericContainer<?> getAppContainer() {
        return app;
    }

    @Override
    protected org.testcontainers.containers.JdbcDatabaseContainer<?> getDatabaseContainer() {
        return db;
    }
}
