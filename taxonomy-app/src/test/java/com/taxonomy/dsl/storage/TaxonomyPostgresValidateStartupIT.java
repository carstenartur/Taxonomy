package com.taxonomy.dsl.storage;

import com.taxonomy.catalog.service.TaxonomyService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves that Flyway builds a schema which Hibernate can validate at startup. */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.search.enabled=false",
        "taxonomy.git.bootstrap=false",
        "taxonomy.init.async=true",
        "embedding.enabled=false",
        "llm.mock=true"
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

    @MockitoBean
    private TaxonomyService taxonomyService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DataSource dataSource;

    @Test
    void startsWithKubernetesValidateContractOnAnEmptyDatabase() throws Exception {
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
}
