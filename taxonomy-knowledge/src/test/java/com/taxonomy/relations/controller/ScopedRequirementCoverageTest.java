package com.taxonomy.relations.controller;

import com.taxonomy.relations.model.RequirementCoverage;
import com.taxonomy.relations.repository.RequirementCoverageRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import jakarta.persistence.EntityManager;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.taxonomy.relations.model.RequirementCoverage.scope;
import static com.taxonomy.relations.service.RequirementCoverageService.recordedSource;
import static org.assertj.core.api.Assertions.assertThat;

class ScopedRequirementCoverageTest {
    @Test
    void overlappingRequirementsAreIsolatedAcrossRepositoriesBranchesCommitsAndWorkspaceKeys() {
        String head = "a".repeat(40), older = "b".repeat(40);
        var centralA = RepositoryContext.centralRead("A", "main", "actor");
        var workspace = RepositoryContext.workspace("A", "__shared__", "main", "actor");
        var otherBranch = RepositoryContext.workspace("A", "__shared__", "draft", "actor");
        var otherWorkspace = RepositoryContext.workspace("A", "A2", "main", "actor");
        var centralB = RepositoryContext.centralRead("B", "main", "actor");
        var config = new Configuration().addAnnotatedClass(RequirementCoverage.class)
                .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:coverage-" + UUID.randomUUID())
                .setProperty("hibernate.connection.username", "sa").setProperty("hibernate.hbm2ddl.auto", "create-drop");
        try (var factory = config.buildSessionFactory()) {
            factory.inTransaction(session -> {
                for (var tenant : List.of(centralA, workspace, otherBranch, otherWorkspace, centralB)) {
                    session.persist(new RequirementCoverage(recordedSource(tenant, head), "R", tenant.scope().name(), "N", 82.5, Instant.EPOCH));
                }
                session.persist(new RequirementCoverage(recordedSource(workspace, older), "R", "historic", "N", 90, Instant.EPOCH));
            });
            try (var session = factory.openSession()) {
                var tx = session.beginTransaction();
                var repository = new JpaRepositoryFactory((EntityManager) session).getRepository(RequirementCoverageRepository.class);
                assertThat(repository.forNode("A", scope(centralA.workspaceId()), "main", head, "N")).singleElement()
                        .satisfies(row -> assertThat(row.getWorkspaceId()).isNull());
                assertThat(repository.density("A", scope(workspace.workspaceId()), "main", head)).singleElement()
                        .satisfies(pair -> assertThat(pair).containsExactly("N", 1L));
                assertThat(repository.coveredNodes("A", scope(workspace.workspaceId()), "main", head, 90)).isZero();
                assertThat(repository.requirements("B", scope(centralB.workspaceId()), "main", head)).isOne();
                assertThat(repository.remove("A", scope(workspace.workspaceId()), "main", head, "R")).isOne();
                tx.commit();
            }
            try (var session = factory.openSession()) {
                var repository = new JpaRepositoryFactory((EntityManager) session).getRepository(RequirementCoverageRepository.class);
                assertThat(repository.forNode("A", scope(workspace.workspaceId()), "main", head, "N")).isEmpty();
                for (var other : List.of(centralA, otherBranch, otherWorkspace, centralB)) {
                    assertThat(repository.forRequirement(other.repositoryId(), scope(other.workspaceId()), other.branch(), head, "R")).hasSize(1);
                }
                assertThat(repository.forRequirement("A", scope(workspace.workspaceId()), "main", older, "R")).singleElement()
                        .satisfies(row -> {
                            assertThat(row.getSourceCommit()).isEqualTo(older);
                            assertThat(row.getRecordedBy()).isEqualTo("actor");
                            assertThat(row.getAnalyzedAt()).isEqualTo(Instant.EPOCH);
                        });
            }
        }
    }
}
