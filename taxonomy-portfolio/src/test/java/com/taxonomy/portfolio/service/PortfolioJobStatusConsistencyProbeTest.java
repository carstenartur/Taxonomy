package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic real READ_COMMITTED interleaving, without provider or worker timing. */
class PortfolioJobStatusConsistencyProbeTest {
    @ParameterizedTest @ValueSource(strings = {"job", "jobs"})
    void continuationCommittedBetweenStatusQueriesCannotCreateAFalseTerminalView(String route) {
        try (var f = new PortfolioStatusPayloadReadTest.Fixture()) {
            f.transaction(em -> {
                var item = em.createQuery("select i from RequirementAnalysisJobItem i where i.jobId=:id", RequirementAnalysisJobItem.class)
                        .setParameter("id", f.jobId).getSingleResult();
                item.complete(AnalysisStatus.PARTIAL, f.oldSnapshotId, PortfolioStatusPayloadReadTest.Fixture.TIME, "CALL_BUDGET");
                item.getJob().complete(0, 1, 0, "CALL_BUDGET", PortfolioStatusPayloadReadTest.Fixture.TIME);
                em.find(ProjectRequirement.class, f.requirementId).pointToVersion(f.oldVersionId, PortfolioStatusPayloadReadTest.Fixture.TIME);
            });
            var initial = f.persistence.getJob(f.jobId, f.projectId, "owner", f.scope);
            assertThat(initial.status()).isEqualTo(AnalysisStatus.PARTIAL);
            assertThat(initial.items().getFirst().status()).isEqualTo(AnalysisStatus.PARTIAL);
            int priorAttempt = initial.items().getFirst().attempt();
            AtomicBoolean committed = new AtomicBoolean();
            f.sqlHook = statement -> {
                String sql = statement.toLowerCase(Locale.ROOT);
                if (sql.startsWith("select") && sql.contains("req_analysis_item") && committed.compareAndSet(false, true)) {
                    System.out.println("INTERLEAVE route=" + route + " sql=" + statement);
                    var writer = new TransactionTemplate(f.transactions);
                    writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    writer.executeWithoutResult(status -> {
                        var item = f.shared.createQuery("select i from RequirementAnalysisJobItem i where i.jobId=:id", RequirementAnalysisJobItem.class)
                                .setParameter("id", f.jobId).getSingleResult();
                        // Exactly the mutations of the admitted prepare path: retain snapshot/version,
                        // increment the item generation and reopen the aggregate before dispatch.
                        item.prepareBudgetContinuation();
                        item.getJob().markPending();
                    });
                }
            };
            f.resetReads();
            List<AnalysisJobView> results = route.equals("job")
                    ? List.of(f.persistence.getJob(f.jobId, f.projectId, "owner", f.scope))
                    : f.persistence.listJobs(f.projectId, "owner", f.scope);
            f.sqlHook = statement -> { };
            assertThat(committed).as("The independent write really committed between the status reads").isTrue();
            assertThat(results).hasSize(1);
            var observed = results.getFirst();
            var item = observed.items().getFirst();
            System.out.println("OBSERVED route=" + route + " job=" + observed.status() + " item=" + item.status()
                    + " attempt=" + item.attempt() + " previousAttempt=" + priorAttempt
                    + " snapshotRetained=" + item.snapshotId().equals(f.oldSnapshotId));
            var actual = f.persistence.getJob(f.jobId, f.projectId, "owner", f.scope);
            assertThat(actual.status()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(item.status()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(item.attempt()).isEqualTo(priorAttempt + 1);
            assertThat(item.snapshotId()).isEqualTo(f.oldSnapshotId);
            assertThat(observed.status()).as("A job with the newly admitted PENDING item cannot be reported terminal")
                    .isEqualTo(AnalysisStatus.PENDING);
        }
    }
}
