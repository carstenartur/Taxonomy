package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.SolutionDefinition;
import com.taxonomy.portfolio.model.SolutionTaxonomyCoverage;
import com.taxonomy.portfolio.repository.SolutionTaxonomyCoverageRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SolutionProposalEligibilityReadTest {
    @Test
    void preflightRequiresConfirmedExactScopeCoverageWithoutLoadingMutableCatalogueEntities() {
        var wanted = new WorkspaceContext("owner", "workspace", "draft", "repository");
        var foreign = List.of(
                new WorkspaceContext("owner", "workspace", "draft", "other-repository"),
                new WorkspaceContext("owner", "other-workspace", "draft", "repository"),
                new WorkspaceContext("owner", "workspace", "other-branch", "repository"));
        try (var factory = new Configuration()
                .addAnnotatedClass(SolutionDefinition.class)
                .addAnnotatedClass(SolutionTaxonomyCoverage.class)
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:proposal-eligibility-" + UUID.randomUUID() + ";shutdown=true")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.generate_statistics", "true")
                .setProperty("hibernate.search.enabled", "false").buildSessionFactory();
             var entityManager = factory.createEntityManager()) {
            entityManager.getTransaction().begin();
            var solution = solution(wanted);
            entityManager.persist(solution);
            entityManager.persist(new SolutionTaxonomyCoverage(solution, "BP-1000", 70,
                    "Awaiting review", ReviewStatus.PROPOSED, "owner", Instant.EPOCH));
            entityManager.persist(new SolutionTaxonomyCoverage(solution, "BP-1001", 70,
                    "Confirmed current scope", ReviewStatus.CONFIRMED, "owner", Instant.EPOCH));
            for (var context : foreign) {
                var other = solution(context);
                entityManager.persist(other);
                entityManager.persist(new SolutionTaxonomyCoverage(other, "BP-1000", 70,
                        "Confirmed foreign scope", ReviewStatus.CONFIRMED, "owner", Instant.EPOCH));
            }
            entityManager.getTransaction().commit();
            entityManager.clear();
            factory.getStatistics().clear();
            var repository = new JpaRepositoryFactory(entityManager)
                    .getRepository(SolutionTaxonomyCoverageRepository.class);
            String scope = PortfolioScope.key("owner", wanted);

            assertThat(repository.existsByNodeCodeAndReviewStatusAndSolutionScopeKey(
                    "BP-1000", ReviewStatus.CONFIRMED, scope)).isFalse();
            assertThat(repository.existsByNodeCodeAndReviewStatusAndSolutionScopeKey(
                    "BP-1001", ReviewStatus.CONFIRMED, scope)).isTrue();
            assertThat(repository.existsByNodeCodeAndReviewStatusAndSolutionScopeKey(
                    "BP-missing", ReviewStatus.CONFIRMED, scope)).isFalse();
            assertThat(factory.getStatistics().getEntityLoadCount())
                    .as("Preflight must leave mutable coverage and solution entities out of the first-level cache")
                    .isZero();
        }
    }

    private static SolutionDefinition solution(WorkspaceContext scope) {
        return new SolutionDefinition(PortfolioScope.key("owner", scope), scope.workspaceId(),
                "SOLUTION", "Scoped solution", "", null, null, null, 1, "owner", "", Instant.EPOCH);
    }
}
