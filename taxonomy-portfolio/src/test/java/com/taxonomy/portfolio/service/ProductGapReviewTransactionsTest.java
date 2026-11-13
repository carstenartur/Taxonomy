package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.ProductGapReview;
import com.taxonomy.portfolio.model.ProductGapReview.Classification;
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProductGapReviewRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductGapReviewTransactionsTest {
    @Test
    void twoReviewersOfOneRevisionAppendOneDecisionAndStaleEvidenceCannotUpdateIt() throws Exception {
        var database = new Configuration().addAnnotatedClass(ArchitectureProject.class).addAnnotatedClass(ProductGapReview.class)
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:gap-" + UUID.randomUUID() + ";shutdown=true")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").setProperty("hibernate.search.enabled", "false");
        try (var factory = database.buildSessionFactory(); var workers = Executors.newFixedThreadPool(2)) {
            var tx = new TransactionTemplate(new JpaTransactionManager(factory));
            var em = SharedEntityManagerCreator.createSharedEntityManager(factory);
            var repositories = new JpaRepositoryFactory(em);
            var context = new WorkspaceContext("reviewer", "workspace", "draft", "repository");
            String scope = PortfolioScope.key("reviewer", context);
            Long project = tx.execute(status -> {
                var row = new ArchitectureProject(scope, "workspace", "reviewer", "GAP", "Project", "", ProjectStatus.ACTIVE, Instant.EPOCH);
                em.persist(row); em.flush();
                return row.getId();
            });
            var reader = mock(ProjectGapEvidenceReader.class);
            var family = new ProjectProductGaps.Family("F", "Family", "frozen",
                    List.of(new ProjectProductGaps.Observation("s1", 1L, "R", 2L, 1, "frozen", 50, 70, "Unmet", List.of())));
            when(reader.read(project, null, scope)).thenReturn(List.of(family));
            var projects = new ProjectPortfolioService(repositories.getRepository(ArchitectureProjectRepository.class),
                    null, null, null, null, null, null, null);
            var service = new ProjectProductGapReviewService(projects, reader, repositories.getRepository(ProductGapReviewRepository.class));
            var ask = new ProjectProductGapReviewService.ReviewRequest(0, family.evidenceFingerprint(), Classification.CREATE, "New capability");
            var barrier = new CyclicBarrier(2);
            Callable<String> write = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    tx.executeWithoutResult(status -> service.review(project, family.reviewKey(), ask, "reviewer", context));
                    return "saved";
                } catch (PortfolioException changed) {
                    assertThat(changed.getKind()).isEqualTo(PortfolioException.Kind.CONFLICT);
                    return "conflict";
                }
            };
            var first = workers.submit(write); var second = workers.submit(write);
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("saved", "conflict");
            var result = tx.execute(status -> service.list(project, "reviewer", context)).getFirst();
            assertThat(result.history()).hasSize(1);
            assertThat(result.classification()).isEqualTo(Classification.CREATE);
            assertThat(result.revision()).isOne();
            var stale = new ProjectProductGapReviewService.ReviewRequest(1, "older", Classification.CHANGE, "Updated");
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.review(project, family.reviewKey(), stale, "reviewer", context)))
                    .isInstanceOf(PortfolioException.class).hasMessageContaining("changed");
            var other = new WorkspaceContext("reviewer", "foreign", "draft", "repository");
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.list(project, "reviewer", other)))
                    .isInstanceOf(PortfolioException.class).hasMessageContaining("not found");
            var secondReview = new ProjectProductGapReviewService.ReviewRequest(1, family.evidenceFingerprint(), Classification.PROCURE, "Existing offer");
            var updated = tx.execute(status -> service.review(project, family.reviewKey(), secondReview, "reviewer", context));
            assertThat(updated.history()).extracting(ProjectProductGapReviewService.Decision::classification)
                    .containsExactly(Classification.CREATE, Classification.PROCURE);
        }
    }
}
