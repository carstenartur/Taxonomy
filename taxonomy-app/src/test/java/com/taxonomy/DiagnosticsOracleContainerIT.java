package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.CoreEntities;
import io.github.carstenartur.jgit.storage.hibernate.config.JgitOracleDialect;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs diagnostics, API and durable cluster execution contracts
 * but against an <strong>Oracle Database Free</strong> backend.
 * <p>
 * Included in the Maven-owned Oracle compatibility lane (requires Docker):
 * <pre>
 * ./mvnw -B verify -Pdatabase-oracle
 * </pre>
 */
@Testcontainers
@Tag("db-oracle")
class DiagnosticsOracleContainerIT extends AbstractExternalDatabaseContainerIT {

    static Network network = Network.newNetwork();

    @Container
    static OracleContainer db = ContainerTestUtils.oracleContainer(network)
            .withStartupTimeout(Duration.ofMinutes(3));

    @Container
    static GenericContainer<?> app = ContainerTestUtils.oracleAppContainer(network)
            .dependsOn(db);

    @Override
    protected GenericContainer<?> getAppContainer() {
        return app;
    }

    @Override
    protected org.testcontainers.containers.JdbcDatabaseContainer<?> getDatabaseContainer() {
        return db;
    }

    @Test
    @Order(24)
    void jgitRepositoryAndHistorySurviveReopeningOnOracle() throws Exception {
        RepositoryName name = new RepositoryName("oracle-reopen-" + UUID.randomUUID());
        String dsl = "element BP-1 type Capability { title: \"Oracle persistence\"; }\n";
        String commit;
        try (SessionFactory first = jgitSessionFactory()) {
            var factory = new DefaultHibernateRepositoryFactory(first);
            try (var repository = new DslGitRepository(factory, name.value())) {
                commit = repository.commitDsl("draft", dsl, "oracle-test", "Oracle storage test");
            }
        }
        try (SessionFactory reopened = jgitSessionFactory()) {
            var factory = new DefaultHibernateRepositoryFactory(reopened);
            try {
                try (var repository = new DslGitRepository(factory, name.value())) {
                    assertThat(repository.getHeadCommit("draft")).isEqualTo(commit);
                    assertThat(repository.getDslAtHead("draft")).isEqualTo(dsl);
                    assertThat(repository.getDslHistory("draft")).hasSize(1);
                }
            } finally {
                factory.deleteRepository(name);
            }
        }
    }

    private static SessionFactory jgitSessionFactory() {
        Configuration configuration = new Configuration();
        CoreEntities.annotatedClasses().forEach(configuration::addAnnotatedClass);
        configuration.setProperty("hibernate.connection.url", db.getJdbcUrl());
        configuration.setProperty("hibernate.connection.username", db.getUsername());
        configuration.setProperty("hibernate.connection.password", db.getPassword());
        configuration.setProperty("hibernate.connection.driver_class", db.getDriverClassName());
        configuration.setProperty("hibernate.dialect", JgitOracleDialect.class.getName());
        configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        return configuration.buildSessionFactory();
    }
}
