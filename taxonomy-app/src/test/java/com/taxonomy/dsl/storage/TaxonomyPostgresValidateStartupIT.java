package com.taxonomy.dsl.storage;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the application migration stream creates a PostgreSQL schema which
 * Hibernate can validate. The minimal test application enables only Boot's
 * database/JPA auto-configuration and entity scanning, avoiding the web, search,
 * document-import and LLM subsystems in the constrained compatibility runner.
 */
@SpringBootTest(
        classes = TaxonomyPostgresValidateStartupIT.MinimalJpaValidationApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.search.enabled=false",
                "spring.flyway.enabled=true"
        })
@ActiveProfiles({"postgres", "kubernetes"})
@Testcontainers
@Tag("db-postgres")
class TaxonomyPostgresValidateStartupIT {

    @Container
    @SuppressWarnings("rawtypes")
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("taxonomy")
            .withUsername("taxonomy")
            .withPassword("taxonomy");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DataSource dataSource;

    @Test
    void validatesTheKubernetesPostgresSchemaOnAnEmptyDatabase() throws Exception {
        assertThat(entityManagerFactory.isOpen()).isTrue();
        try (Connection connection = dataSource.getConnection();
             ResultSet table = connection.getMetaData().getTables(
                     connection.getCatalog(),
                     connection.getSchema(),
                     "req_analysis_snapshot",
                     new String[] {"TABLE"})) {
            assertThat(table.next()).isTrue();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackages = {
            "com.taxonomy",
            "io.github.carstenartur.jgit.storage.hibernate.entity"
    })
    @Import(TaxonomySchemaMigrationConfig.class)
    static class MinimalJpaValidationApplication {
    }
}
