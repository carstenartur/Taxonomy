package com.taxonomy.composition.persistence;

import com.taxonomy.analysis.recovery.AnalysisContinuationStore;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.storage.JgitStorageSchemaMigrationConfig;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
@Tag("docker-required")
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

    @Autowired
    private AnalysisContinuationStore continuations;

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

    @Test
    void resolvesRelationsInputWithItsOwnPostgresLockTransaction() {
        assertThat(AopUtils.isAopProxy(continuations)).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var scope = new WorkspaceContext("alice", "relations-" + UUID.randomUUID(), "draft", "repo");
        var node = new TaxonomyNodeDto(); node.setCode("CP"); node.setTaxonomyRoot("CP"); node.setNameEn("Original capability");
        var tree = List.of(node);
        var sourceRequest = new AnalysisRequest("Original requirement"); sourceRequest.setProvider("MOCK");
        sourceRequest.setContinuationId(UUID.randomUUID().toString()); sourceRequest.setResumable(true);
        sourceRequest.setMaxArchitectureNodes(20);
        sourceRequest.setAnalysisScope(new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY));
        var sourceClaim = continuations.begin(sourceRequest, scope, "a".repeat(64), tree);
        var result = new AnalysisResult(Map.of("CP", 71.125), tree); result.setStatus("SUCCESS"); result.setProvider("MOCK");
        result.setReasons(Map.of("CP", "Original retained reasoning"));
        continuations.complete(sourceClaim, result, tree);
        var source = continuations.read(sourceRequest.getContinuationId(), scope);
        assertThat(source.recovery().state()).isEqualTo("COMPLETED");
        assertThat(source.result().getAnalysisCoverage()).isNotNull();

        var extension = new AnalysisRequest("Original requirement"); extension.setProvider("MOCK"); extension.setMaxArchitectureNodes(20);
        extension.setContinuationId(UUID.randomUUID().toString()); extension.setResumable(true);
        extension.setAnalysisScope(new AnalysisScope(Set.of("CP"), AnalysisMode.FULL));
        extension.setRelationSourceContinuationId(source.recovery().id()); extension.setRelationSourceVersion(source.recovery().version());
        assertThat(continuations.originalRequest(extension.getContinuationId(), scope)).isEmpty();

        // Call the Spring proxy without an ambient test transaction. The absent target
        // path must acquire its real PostgreSQL shared lock inside relationInput itself.
        var input = continuations.relationInput(extension, scope);
        assertThat(input.recovery()).isEqualTo(source.recovery());
        assertThat(input.request().getAnalysisScope()).isEqualTo(sourceRequest.getAnalysisScope());
        assertThat(input.result().getRawScores()).containsExactlyEntriesOf(result.getRawScores());
        assertThat(input.result().getReasons()).isEqualTo(result.getReasons());
        assertThat(input.result().getAnalysisCoverage()).isEqualTo(source.result().getAnalysisCoverage());
        assertThat(continuations.originalRequest(extension.getContinuationId(), scope)).isEmpty();

        extension.setRelationSourceVersion(source.recovery().version() + 1);
        assertThatThrownBy(() -> continuations.relationInput(extension, scope)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        extension.setRelationSourceVersion(source.recovery().version());
        var foreign = new WorkspaceContext("bob", scope.workspaceId(), scope.currentBranch(), scope.repositoryId());
        assertThatThrownBy(() -> continuations.relationInput(extension, foreign)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        assertThat(continuations.read(source.recovery().id(), scope).recovery()).isEqualTo(source.recovery());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackages = {
            "com.taxonomy",
            "io.github.carstenartur.jgit.storage.hibernate.entity"
    })
    @Import({
            JgitStorageSchemaMigrationConfig.class,
            TaxonomySchemaMigrationConfig.class,
            AnalysisContinuationStore.class
    })
    static class MinimalJpaValidationApplication {
    }
}
