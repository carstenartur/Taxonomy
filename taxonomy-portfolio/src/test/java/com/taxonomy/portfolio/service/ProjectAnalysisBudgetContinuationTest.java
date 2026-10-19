package com.taxonomy.portfolio.service;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.analysis.recovery.AnalysisContinuationRun;
import com.taxonomy.analysis.recovery.AnalysisContinuationService;
import com.taxonomy.analysis.recovery.AnalysisContinuationStore;
import com.taxonomy.analysis.recovery.AnalysisQuestionCheckpoint;
import com.taxonomy.analysis.relations.RequirementRelationSearchService;
import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.architecture.service.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.repository.*;
import com.taxonomy.portfolio.reformulation.ReformulationPositiveReviewGuard;
import com.taxonomy.relations.service.AnalysisHypothesisLinkPort;
import com.taxonomy.relations.service.HypothesisService;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.service.*;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static com.taxonomy.dto.RelationSearchModel.*;
import static com.taxonomy.portfolio.model.PortfolioTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SQL, claims, durable questions, relation procedure and immutable snapshots; no external provider. */
class ProjectAnalysisBudgetContinuationTest {
    @TempDir Path database;

    @Test
    void continuationReadChecksExactIdentityWithoutHydratingUnusedPayloads() throws Exception {
        try (var f = new Fixture(database)) {
            String note = "Unused continuation enrichment evidence Ä水😀. ".repeat(160);
            var gap = new GapAnalysisView();
            gap.setBusinessText(Fixture.TEXT); gap.setNotes(List.of("GAP " + note));
            var pattern = new PatternDetectionView();
            pattern.setNodeCode("BP"); pattern.setNotes(List.of("PATTERN " + note));
            var recommendation = new ArchitectureRecommendation();
            recommendation.setBusinessText(Fixture.TEXT);
            recommendation.setNotes(List.of("RECOMMENDATION " + note));
            // The ordinary producer persists these results; no immutable snapshot is rewritten.
            when(((ArchitectureGapService) ReflectionTestUtils.getField(f.service, "gapService"))
                    .analyze(anyMap(), anyString(), anyInt())).thenReturn(gap);
            when(((ArchitecturePatternService) ReflectionTestUtils.getField(f.service, "patternService"))
                    .detectForScores(anyMap(), anyInt())).thenReturn(pattern);
            when(((ArchitectureRecommendationService) ReflectionTestUtils.getField(f.service, "recommendationService"))
                    .recommend(anyMap(), anyString(), anyInt())).thenReturn(recommendation);

            var job = f.start("continuation-payload-read");
            var item = job.items().getFirst();
            assertThat(job.status()).isEqualTo(AnalysisStatus.PARTIAL);
            assertThat(item.status()).isEqualTo(AnalysisStatus.PARTIAL);
            try (var connection = DriverManager.getConnection(f.url);
                 var statement = connection.prepareStatement("select job_id, project_id, scope_key, requirement_id, "
                         + "requirement_version_id, analysis_payload, gap_payload, pattern_payload, recommendation_payload "
                         + "from req_analysis_snapshot where id=?")) {
                statement.setString(1, item.snapshotId());
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(job.id());
                    assertThat(rows.getLong(2)).isEqualTo(f.projectId);
                    assertThat(rows.getString(3)).isEqualTo(PortfolioScope.key("owner", f.scope));
                    assertThat(rows.getLong(4)).isEqualTo(f.requirementId);
                    assertThat(rows.getLong(5)).isEqualTo(item.requirementVersionId());
                    assertThat(rows.getString(6)).isNotBlank();
                    for (int column = 7; column <= 9; column++)
                        assertThat(rows.getString(column)).contains("Unused continuation enrichment evidence");
                    assertThat(rows.next()).isFalse();
                }
            }
            assertThatThrownBy(() -> f.service.getContinuation(job.id(), f.projectId, item.id(), "owner",
                    new WorkspaceContext("owner", "other-workspace", "draft", "repository")))
                    .isInstanceOfSatisfying(PortfolioException.class, error ->
                            assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
            assertThatThrownBy(() -> f.service.getContinuation("other-job", f.projectId, item.id(), "owner", f.scope))
                    .isInstanceOfSatisfying(PortfolioException.class, error ->
                            assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
            assertThatThrownBy(() -> f.service.getContinuation(job.id(), f.projectId, Long.MAX_VALUE, "owner", f.scope))
                    .isInstanceOfSatisfying(PortfolioException.class, error ->
                            assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));

            var bindingChecks = new AtomicInteger();
            f.providerBinding = (project, targets, requested, username, context) -> {
                assertThat(project).isEqualTo(f.projectId);
                assertThat(username).isEqualTo("owner");
                assertThat(context).isEqualTo(f.scope);
                assertThat(requested).isNull();
                assertThat(targets).containsExactly(new ProjectProviderBinding.Target(
                        f.requirementId, item.requirementVersionId()));
                bindingChecks.incrementAndGet();
                return Optional.of(job.provider());
            };
            int callsBefore = f.prompts.size();
            f.sql.clear();
            var eligibility = f.eligibility(job);
            var selects = f.sql.stream().map(sql -> sql.toLowerCase(Locale.ROOT))
                    .filter(sql -> sql.stripLeading().startsWith("select")).toList();
            assertThat(eligibility.available()).as(eligibility.reason()).isTrue();
            assertThat(eligibility.snapshotId()).isEqualTo(item.snapshotId());
            assertThat(eligibility.requirementVersionId()).isEqualTo(item.requirementVersionId());
            assertThat(eligibility.continuationId()).isNotBlank();
            assertThat(eligibility.continuationVersion()).isNotNegative();
            assertThat(eligibility.state()).isEqualTo("STOPPED");
            assertThat(bindingChecks).hasValue(1);
            assertThat(f.prompts).hasSize(callsBefore);
            assertThat(f.classifications).hasValue(1);
            assertThat(f.queue).isEmpty();
            assertThat(selects).as("Continuation eligibility needs no enrichment or requirement-text LOB")
                    .noneMatch(sql -> sql.contains("gap_payload") || sql.contains("pattern_payload")
                            || sql.contains("recommendation_payload") || sql.contains("requirement_text")
                            || sql.contains("source_fragment_ids") || sql.contains("original_text"));
            assertThat(selects.stream().filter(sql -> sql.contains("analysis_payload")).toList())
                    .singleElement().satisfies(sql -> {
                        String normalized = sql.replaceAll("\\s+", " ");
                        assertThat(normalized).contains(" where ");
                        assertThat(normalized.substring(normalized.indexOf(" where ")).replaceAll("\\s+", ""))
                                .contains(".id=", ".job_id=", ".project_id=", ".scope_key=",
                                        ".requirement_id=", ".requirement_version_id=");
                    });
            assertThat(selects.stream().filter(sql -> sql.contains("req_analysis_item")).toList())
                    .as("Job, item, current version and snapshot identity come from one scalar statement")
                    .singleElement().satisfies(sql -> assertThat(sql)
                            .contains("req_analysis_job", "project_requirement", "req_analysis_snapshot", "current_version_id"));

            f.sql.clear();
            var prepared = f.continueWith(job, new ContinueAnalysisRequest(
                    eligibility.snapshotId(), eligibility.continuationVersion()));
            assertThat(prepared.status()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(f.prompts).hasSize(callsBefore);
            assertThat(f.classifications).hasValue(1);
            assertThat(f.queue).hasSize(1);
            assertThat(f.sql.stream().map(sql -> sql.toLowerCase(Locale.ROOT))
                    .filter(sql -> sql.stripLeading().startsWith("select")).toList())
                    .as("Preparing a cycle needs its complete analysis, without hydrating unrelated enrichment CLOBs")
                    .noneMatch(sql -> sql.contains("gap_payload") || sql.contains("pattern_payload")
                            || sql.contains("recommendation_payload"));
        }
    }

    @Test
    void explicitCycleSurvivesDatabaseRestartAndCompletesSavedSnapshotWithoutRepeatingSuccessfulQuestions() throws Exception {
        try (var f = new Fixture(database)) {
            var first = f.start("regular");
            assertThat(first.status()).isEqualTo(AnalysisStatus.PARTIAL);
            var original = f.snapshot(first);
            var originalJson = f.json.writeValueAsString(original.analysis());
            var report = original.analysis().getRelationSearchReport();
            assertThat(report.maxCalls()).isEqualTo(24);
            assertThat(report.totalCalls()).isEqualTo(24);
            assertThat(report.progress().totalSources()).isEqualTo(33);
            assertThat(report.progress().assessedSources()).isEqualTo(32);
            assertThat(report.result().unfinished()).anyMatch(work -> work.reason().equals("CALL_BUDGET"));
            assertThat(original.analysis().getRecovery().state()).isEqualTo("STOPPED");
            assertThat(f.prompts).hasSize(24).doesNotHaveDuplicates();
            assertThat(f.classifications.get()).isEqualTo(1);

            var eligibility = f.eligibility(first);
            assertThat(eligibility.available()).as(eligibility.reason()).isTrue();
            int callsBeforeDecision = f.prompts.size();
            assertThatThrownBy(() -> f.continueWith(first,
                    new ContinueAnalysisRequest(eligibility.snapshotId(), eligibility.continuationVersion() - 1)))
                    .isInstanceOf(PortfolioException.class).hasMessageContaining("revision");
            assertThat(f.prompts).hasSize(callsBeforeDecision);
            assertThat(f.current(first).status()).isEqualTo(AnalysisStatus.PARTIAL);

            var accepted = f.continueWith(first, new ContinueAnalysisRequest(
                    eligibility.snapshotId(), eligibility.continuationVersion()));
            assertThat(accepted.status()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(accepted.items().getFirst().snapshotId()).isEqualTo(eligibility.snapshotId());
            assertThat(accepted.items().getFirst().attempt()).isEqualTo(2);
            assertThat(f.prompts).hasSize(callsBeforeDecision);
            assertThatThrownBy(() -> f.continueWith(first, new ContinueAnalysisRequest(
                    eligibility.snapshotId(), eligibility.continuationVersion())))
                    .isInstanceOf(PortfolioException.class);
            assertThat(f.queue).hasSize(1);

            // Destroy the factory, SQL connections, repositories, services and in-memory worker queue.
            f.restart();
            assertThat(f.current(first).status()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(f.eligibility(first).available()).isFalse();
            assertThat(f.prompts).hasSize(callsBeforeDecision);
            // Re-dispatch only the already authorized persisted PENDING claim.
            f.service.enqueueRetryFailed(first.id(), f.projectId, "owner", f.scope);
            f.runQueued();
            var completed = f.current(first);
            assertThat(completed.status()).as(completed.errorSummary()).isEqualTo(AnalysisStatus.SUCCESS);
            assertThat(completed.id()).isEqualTo(first.id());
            assertThat(completed.items().getFirst().requirementVersionId())
                    .isEqualTo(first.items().getFirst().requirementVersionId());
            var latest = f.snapshot(completed);
            assertThat(latest.analysis().getRecovery().id()).isEqualTo(eligibility.continuationId());
            assertThat(latest.analysis().getRecovery().state()).isEqualTo("COMPLETED");
            assertThat(latest.analysis().getRecovery().openQuestions()).isEmpty();
            assertThat(latest.analysis().getRelationSearchReport().maxCalls()).isEqualTo(24);
            assertThat(latest.analysis().getRelationSearchReport().totalCalls()).isBetween(1, 24);
            assertThat(latest.analysis().getRelationSearchReport().progress().assessedSources()).isEqualTo(33);
            assertThat(latest.analysis().getRelationSearchReport().isSearchExhausted()).isTrue();
            assertThat(latest.analysis().getRelationSearchReport().result().edges())
                    .extracting(Edge::sourceId).containsExactlyInAnyOrder("BP-000", "BP-001", "BP-002", "BP-003", "BP-032");
            assertThat(latest.relationMappings()).hasSize(5);
            assertThat(latest.analysis().getScores()).containsEntry("BP-032", 0.5d);
            assertThat(f.prompts).doesNotHaveDuplicates();
            assertThat(f.classifications.get()).isEqualTo(1);
            assertThat(f.service.listSnapshots(f.projectId, f.requirementId, "owner", f.scope)).hasSize(2);
            assertThat(f.json.writeValueAsString(f.service.getSnapshot(f.projectId,
                    original.summary().id(), "owner", f.scope).analysis())).isEqualTo(originalJson);
            assertThat(f.eligibility(completed).available()).isFalse();
            assertThatThrownBy(() -> f.continueWith(first,
                    new ContinueAnalysisRequest(eligibility.snapshotId(), eligibility.continuationVersion())))
                    .isInstanceOf(PortfolioException.class);
            assertThat(AnalysisCheckpointSession.active()).isFalse();
        }
    }

    @Test
    void sourceCohortLimitAlsoRequiresAnExplicitActionEvenWithUnusedCallBudget() throws Exception {
        try (var f = new Fixture(database)) {
            f.explicitSources = Set.of("BP-032");
            var first = f.start("cohort");
            var report = f.snapshot(first).analysis().getRelationSearchReport();
            assertThat(first.status()).isEqualTo(AnalysisStatus.PARTIAL);
            assertThat(report.totalCalls()).isEqualTo(11);
            assertThat(report.warnings()).anyMatch(warning -> warning.startsWith("SOURCE_LIMIT:"));
            assertThat(report.progress().pendingSearches()).isGreaterThan(0);
            var decision = f.eligibility(first);
            assertThat(decision.available()).as(decision.reason()).isTrue();
            f.continueWith(first, new ContinueAnalysisRequest(decision.snapshotId(), decision.continuationVersion()));
            f.runQueued();
            var completed = f.current(first);
            assertThat(completed.status()).as(completed.errorSummary()).isEqualTo(AnalysisStatus.SUCCESS);
            assertThat(f.snapshot(completed).relationMappings()).singleElement()
                    .satisfies(edge -> assertThat(edge.sourceCode()).isEqualTo("BP-032"));
            assertThat(f.prompts).doesNotHaveDuplicates();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"closed", "changed"})
    void changedProjectProviderAdmissionCannotQueueAnotherCycle(String change) throws Exception {
        try (var f = new Fixture(database)) {
            var first = f.start("provider-admission");
            var decision = f.eligibility(first);
            var before = f.json.writeValueAsString(f.snapshot(first).analysis());
            int calls = f.prompts.size();
            List<ProjectProviderBinding.Target> expected = List.of(new ProjectProviderBinding.Target(
                    f.requirementId, first.items().getFirst().requirementVersionId()));
            f.providerBinding = (projectId, targets, requested, username, context) -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(projectId).isEqualTo(f.projectId);
                assertThat(targets).isEqualTo(expected);
                assertThat(username).isEqualTo("owner");
                assertThat(context).isEqualTo(f.scope);
                if (change.equals("closed")) throw PortfolioException.conflict("Project provider binding is closed");
                return Optional.of("ANOTHER_PROVIDER");
            };
            assertThatThrownBy(() -> f.continueWith(first,
                    new ContinueAnalysisRequest(decision.snapshotId(), decision.continuationVersion())))
                    .isInstanceOfSatisfying(PortfolioException.class, problem -> {
                        assertThat(problem.getKind()).isEqualTo(PortfolioException.Kind.CONFLICT);
                        assertThat(problem.getMessage()).containsIgnoringCase(change);
                    });
            assertThat(f.eligibility(first).available()).isFalse();
            assertThat(f.queue).isEmpty();
            assertThat(f.prompts).hasSize(calls);
            assertThat(f.current(first).items().getFirst().attempt()).isEqualTo(first.items().getFirst().attempt());
            assertThat(f.current(first).status()).isEqualTo(AnalysisStatus.PARTIAL);
            assertThat(f.json.writeValueAsString(f.snapshot(first).analysis())).isEqualTo(before);
        }
    }

    @Test
    void providerAdmissionIsRecheckedBeforeAQueuedContinuationOpensItsJournal() throws Exception {
        try (var f = new Fixture(database)) {
            var first = f.start("provider-closed-while-queued");
            var decision = f.eligibility(first);
            var before = f.json.writeValueAsString(f.snapshot(first).analysis());
            int calls = f.prompts.size();
            f.continueWith(first, new ContinueAnalysisRequest(decision.snapshotId(), decision.continuationVersion()));
            f.providerBinding = (projectId, targets, requested, username, context) -> {
                throw PortfolioException.conflict("Project provider binding is closed");
            };
            f.runQueued();
            assertThat(f.current(first).status()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(f.current(first).errorSummary()).contains("closed");
            assertThat(f.prompts).hasSize(calls);
            assertThat(f.classifications.get()).isEqualTo(1);
            assertThat(f.json.writeValueAsString(f.snapshot(first).analysis())).isEqualTo(before);
        }
    }

    @Test
    void unchangedProjectBindingContinuesWithExactlyTheFrozenProviderAndRequirementVersion() throws Exception {
        try (var f = new Fixture(database)) {
            var first = f.start("provider-still-admitted");
            var decision = f.eligibility(first);
            AtomicInteger admissions = new AtomicInteger();
            f.providerBinding = (projectId, targets, requested, username, context) -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(targets).containsExactly(new ProjectProviderBinding.Target(f.requirementId,
                        first.items().getFirst().requirementVersionId()));
                admissions.incrementAndGet();
                return Optional.of(first.provider());
            };
            f.continueWith(first, new ContinueAnalysisRequest(decision.snapshotId(), decision.continuationVersion()));
            f.runQueued();
            var completed = f.current(first);
            assertThat(completed.status()).as(completed.errorSummary()).isEqualTo(AnalysisStatus.SUCCESS);
            assertThat(completed.provider()).isEqualTo(first.provider());
            assertThat(completed.items().getFirst().requirementVersionId())
                    .isEqualTo(first.items().getFirst().requirementVersionId());
            assertThat(f.snapshot(completed).analysis().getRecovery().id()).isEqualTo(decision.continuationId());
            assertThat(f.prompts).doesNotHaveDuplicates();
            assertThat(admissions).hasPositiveValue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void acceptedClarificationSurvivesRestartWhileIndependentBudgetWorkCompletes(boolean sourceQuestion) throws Exception {
        try (var f = new Fixture(database)) {
            f.explicitSources = Set.of("BP-000", "BP-032");
            f.clarifySource = sourceQuestion;
            f.clarifyRelation = !sourceQuestion;
            var first = f.start("open-domain-question");
            var original = f.snapshot(first);
            var recovery = original.analysis().getRecovery();
            assertThat(first.status()).isEqualTo(AnalysisStatus.PARTIAL);
            assertThat(recovery.state()).isEqualTo("STOPPED");
            assertThat(recovery.openQuestions()).singleElement().satisfies(question -> {
                assertThat(question.error()).contains(Fixture.OPEN_QUESTION);
                assertThat(question.skipped()).isFalse();
                assertThat(question.outcomeUncertain()).isFalse();
            });
            assertThat(original.analysis().getRelationSearchReport().warnings())
                    .anyMatch(warning -> warning.startsWith("SOURCE_LIMIT:"));
            var question = recovery.openQuestions().getFirst();
            String originalEvidence = f.questionEvidence(recovery.id(), question.key());
            assertThat(f.questionState(recovery.id(), question.key())).isEqualTo("CLARIFICATION");
            int callsBeforeDecision = f.prompts.size();

            f.restart();
            var decision = f.eligibility(first);
            assertThat(decision.available()).as(decision.reason()).isTrue();
            assertThat(f.prompts).hasSize(callsBeforeDecision);
            f.continueWith(first, new ContinueAnalysisRequest(decision.snapshotId(), decision.continuationVersion()));
            f.runQueued();
            var completed = f.current(first);
            var latest = f.snapshot(completed);
            assertThat(completed.status()).isEqualTo(AnalysisStatus.PARTIAL);
            assertThat(latest.analysis().getRecovery().state()).isEqualTo("COMPLETED_WITH_GAPS");
            assertThat(latest.analysis().getRecovery().openQuestions()).containsExactly(question);
            assertThat(f.questionState(recovery.id(), question.key())).isEqualTo("CLARIFICATION");
            assertThat(f.questionEvidence(recovery.id(), question.key())).isEqualTo(originalEvidence);
            var report = latest.analysis().getRelationSearchReport();
            assertThat(report.maxCalls()).isEqualTo(24);
            assertThat(report.totalCalls()).isBetween(1, 24);
            assertThat(report.progress().assessedSources()).isEqualTo(33);
            assertThat(report.progress().pendingSearches()).isZero();
            assertThat(report.progress().unresolvedSearches()).isGreaterThan(0);
            assertThat(report.isSearchExhausted()).isFalse();
            assertThat(report.result().edges()).anyMatch(edge -> edge.sourceId().equals("BP-032"));
            assertThat(f.prompts).doesNotHaveDuplicates();
            assertThat(f.classifications.get()).isEqualTo(1);
            assertThat(f.eligibility(completed).available()).isFalse();
            assertThatThrownBy(() -> f.continueWith(completed, new ContinueAnalysisRequest(
                    latest.summary().id(), latest.analysis().getRecovery().version())))
                    .isInstanceOf(PortfolioException.class);
            assertThat(f.service.listSnapshots(f.projectId, f.requirementId, "owner", f.scope)).hasSize(2);
            assertThat(f.service.getSnapshot(f.projectId, original.summary().id(), "owner", f.scope)
                    .analysis().getRecovery().openQuestions()).containsExactly(question);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNASSESSED", "FAILED", "ATTEMPT", "UNKNOWN"})
    void aClarificationLikeErrorCannotDisguiseABlockingPersistedQuestionState(String actualState) throws Exception {
        try (var f = new Fixture(database)) {
            f.explicitSources = Set.of("BP-000", "BP-032");
            f.clarifyRelation = true;
            var first = f.start("blocked-checkpoint-state");
            var recovery = f.snapshot(first).analysis().getRecovery();
            assertThat(recovery.openQuestions()).hasSize(1);
            f.transaction(em -> assertThat(em.createQuery("update AnalysisQuestionCheckpoint q set q.state=:state "
                            + "where q.run.id=:id and q.state='CLARIFICATION'")
                    .setParameter("id", recovery.id()).setParameter("state", actualState).executeUpdate()).isEqualTo(1));
            int calls = f.prompts.size();
            assertThat(f.eligibility(first).available()).isFalse();
            assertThatThrownBy(() -> f.continueWith(first, new ContinueAnalysisRequest(
                    first.items().getFirst().snapshotId(), recovery.version())))
                    .isInstanceOf(PortfolioException.class);
            assertThat(f.prompts).hasSize(calls);
            assertThat(f.current(first).items().getFirst().attempt()).isEqualTo(1);
        }
    }

    @Test
    void newerRequirementVersionCannotReuseOldContinuationAuthority() throws Exception {
        try (var f = new Fixture(database)) {
            var first = f.start("version");
            var decision = f.eligibility(first);
            int calls = f.prompts.size();
            Long oldVersion = first.items().getFirst().requirementVersionId();
            f.transaction(em -> {
                var requirement = em.find(ProjectRequirement.class, f.requirementId);
                var next = f.version(requirement, 2);
                em.persist(next); em.flush(); requirement.pointToVersion(next.getId(), Instant.now());
            });
            assertThat(f.eligibility(first).available()).isFalse();
            assertThat(f.eligibility(first).reason()).contains("version changed");
            assertThatThrownBy(() -> f.continueWith(first,
                    new ContinueAnalysisRequest(decision.snapshotId(), decision.continuationVersion())))
                    .isInstanceOf(PortfolioException.class).hasMessageContaining("version changed");
            assertThat(f.current(first).items().getFirst().requirementVersionId()).isEqualTo(oldVersion);
            assertThat(f.prompts).hasSize(calls);
            var next = f.start("version-new");
            assertThat(next.items().getFirst().requirementVersionId()).isNotEqualTo(oldVersion);
            assertThat(f.snapshot(next).analysis().getRecovery().id()).isNotEqualTo(decision.continuationId());
        }
    }

    @Test
    void queuedContinuationCanBeCancelledWithoutConsumingAnotherCycle() throws Exception {
        try (var f = new Fixture(database)) {
            var first = f.start("cancel");
            var decision = f.eligibility(first);
            f.continueWith(first, new ContinueAnalysisRequest(decision.snapshotId(), decision.continuationVersion()));
            f.jobControl.cancel(first.id(), f.projectId, "owner", f.scope);
            int calls = f.prompts.size();
            f.runQueued();
            assertThat(f.current(first).status()).isEqualTo(AnalysisStatus.CANCELLED);
            assertThat(f.eligibility(first).available()).isFalse();
            assertThatThrownBy(() -> f.continueWith(first,
                    new ContinueAnalysisRequest(decision.snapshotId(), decision.continuationVersion())))
                    .isInstanceOf(PortfolioException.class);
            assertThat(f.prompts).hasSize(calls);
            assertThat(f.service.listSnapshots(f.projectId, f.requirementId, "owner", f.scope)).hasSize(1);
        }
    }

    @Test
    void anotherTenantCannotReadOrPrepareThePartialItem() throws Exception {
        try (var f = new Fixture(database)) {
            var first = f.start("tenant");
            var decision = f.eligibility(first);
            var other = new WorkspaceContext("owner", "another-workspace", "draft", "repository");
            assertThatThrownBy(() -> f.service.getContinuation(first.id(), f.projectId,
                    first.items().getFirst().id(), "owner", other)).isInstanceOf(PortfolioException.class);
            assertThatThrownBy(() -> f.service.enqueueContinuation(first.id(), f.projectId,
                    first.items().getFirst().id(), new ContinueAnalysisRequest(decision.snapshotId(), decision.continuationVersion()),
                    "owner", other)).isInstanceOf(PortfolioException.class);
            assertThat(f.prompts).hasSize(24);
            assertThat(f.current(first).items().getFirst().attempt()).isEqualTo(1);
        }
    }

    @Test
    void switchingToArtemisCannotSilentlyRunALocalContinuation() throws Exception {
        try (var f = new Fixture(database)) {
            var first = f.start("transport");
            var decision = f.eligibility(first);
            f.transport = "artemis";
            f.restart();
            assertThat(f.eligibility(first).available()).isFalse();
            assertThat(f.eligibility(first).reason()).contains("Artemis");
            assertThatThrownBy(() -> f.continueWith(first,
                    new ContinueAnalysisRequest(decision.snapshotId(), decision.continuationVersion())))
                    .isInstanceOf(PortfolioException.class).hasMessageContaining("Artemis");
            assertThat(f.prompts).hasSize(24);
        }
    }

    @Test
    void copilotReevaluatesExistingPassSnapshotsWhileKeepingVerificationJournalsIndependent() throws Exception {
        try (var f = new Fixture(database)) {
            String operationId = "a".repeat(64);
            var key = new CopilotOperationKey(false, operationId, 1, 2, AnalysisAutomationProfile.EXHAUSTIVE, false, false);
            var first = f.start(key.value());
            var firstQueries = List.copyOf(f.prompts);
            var second = f.start(key.forPass(2).value());
            var firstRecovery = f.eligibility(first);
            var secondRecovery = f.eligibility(second);
            assertThat(firstRecovery.continuationId()).isNotEqualTo(secondRecovery.continuationId());
            assertThat(f.prompts.subList(24, 48)).isEqualTo(firstQueries);
            assertThat(f.classifications.get()).isEqualTo(2);
            var partial = f.copilot.getOperation(f.projectId, operationId, "owner", f.scope);
            assertThat(partial.status()).isEqualTo(AnalysisStatus.PARTIAL);
            assertThat(partial.jobs()).hasSize(2);

            f.continueWith(first, new ContinueAnalysisRequest(firstRecovery.snapshotId(), firstRecovery.continuationVersion()));
            assertThat(f.copilot.getOperation(f.projectId, operationId, "owner", f.scope).status()).isEqualTo(AnalysisStatus.PENDING);
            f.runQueued();
            var once = f.copilot.getOperation(f.projectId, operationId, "owner", f.scope);
            assertThat(once.status()).isEqualTo(AnalysisStatus.PARTIAL);
            assertThat(once.selectedSnapshotId()).isEqualTo(f.current(first).items().getFirst().snapshotId());
            assertThat(once.jobs()).extracting(AnalysisJobView::status)
                    .containsExactly(AnalysisStatus.SUCCESS, AnalysisStatus.PARTIAL);
            assertThat(f.current(second).items().getFirst().attempt()).isEqualTo(1);
            var firstTail = List.copyOf(f.prompts.subList(48, f.prompts.size()));
            assertThat(firstTail).doesNotContainAnyElementsOf(firstQueries);

            f.continueWith(second, new ContinueAnalysisRequest(secondRecovery.snapshotId(), secondRecovery.continuationVersion()));
            int beforeLast = f.prompts.size();
            f.runQueued();
            var completed = f.copilot.getOperation(f.projectId, operationId, "owner", f.scope);
            assertThat(completed.status()).isEqualTo(AnalysisStatus.SUCCESS);
            assertThat(completed.verificationPasses()).isEqualTo(2);
            assertThat(completed.jobs()).hasSize(2);
            assertThat(completed.selectedSnapshotId()).isIn(f.current(first).items().getFirst().snapshotId(),
                    f.current(second).items().getFirst().snapshotId());
            assertThat(completed.selectedSnapshotId()).isNotIn(firstRecovery.snapshotId(), secondRecovery.snapshotId());
            assertThat(f.prompts.subList(beforeLast, f.prompts.size())).isEqualTo(firstTail);
            assertThat(f.classifications.get()).isEqualTo(2);
            assertThat(f.service.listSnapshots(f.projectId, f.requirementId, "owner", f.scope)).hasSize(4);
        }
    }

    @Test
    void cancellationOfAnotherCopilotPassAlsoBlocksBudgetContinuation() throws Exception {
        try (var f = new Fixture(database)) {
            var key = new CopilotOperationKey(false, "b".repeat(64), 1, 2, AnalysisAutomationProfile.EXHAUSTIVE, false, false);
            var first = f.start(key.value());
            var second = f.start(key.forPass(2).value());
            var decision = f.eligibility(first);
            var secondDecision = f.eligibility(second);
            f.continueWith(second, new ContinueAnalysisRequest(secondDecision.snapshotId(), secondDecision.continuationVersion()));
            f.jobControl.cancel(second.id(), f.projectId, "owner", f.scope);
            assertThat(f.eligibility(first).available()).isFalse();
            assertThat(f.eligibility(first).reason()).contains("cancelled");
            assertThatThrownBy(() -> f.continueWith(first,
                    new ContinueAnalysisRequest(decision.snapshotId(), decision.continuationVersion())))
                    .isInstanceOf(PortfolioException.class).hasMessageContaining("cancelled");
            assertThat(f.prompts).hasSize(48);
        }
    }

    private static final class Fixture implements AutoCloseable {
        static final String TEXT = "The business process reads existing evidence.";
        static final String OPEN_QUESTION = "Which existing evidence is retained as a recognized record?";
        final WorkspaceContext scope = new WorkspaceContext("owner", "owned-workspace", "draft", "repository");
        final JsonMapper json = JsonMapper.builder().build();
        final AtomicInteger classifications = new AtomicInteger();
        final List<String> prompts = new ArrayList<>();
        final List<String> sql = new ArrayList<>();
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final String url;
        final TaxonomyService catalogue = mock(TaxonomyService.class);
        final Map<String, TaxonomyNode> nodes = new LinkedHashMap<>();
        final Map<String, Double> scores = new LinkedHashMap<>();
        final List<TaxonomyNodeDto> tree;
        final LlmService llm = mock(LlmService.class);
        Set<String> explicitSources = Set.of("BP-000", "BP-001", "BP-002", "BP-003", "BP-032");
        boolean clarifySource;
        boolean clarifyRelation;
        String transport = "local";
        ProjectProviderBinding providerBinding = ProjectProviderBinding.NONE;
        SessionFactory factory;
        JpaTransactionManager transactions;
        EntityManager shared;
        ProjectRequirementAnalysisService service;
        CopilotJobControlService jobControl;
        CopilotAutomationService copilot;
        Long projectId;
        Long requirementId;

        Fixture(Path directory) throws Exception {
            url = "jdbc:hsqldb:file:" + directory.resolve("continuation");
            node("BP", "BP", null); node("IP", "IP", null); node("IP-record", "IP", "IP");
            for (int i = 0; i < 33; i++) {
                String id = "BP-%03d".formatted(i); node(id, "BP", "BP"); scores.put(id, 0.5d);
            }
            scores.put("BP", 16.5d); scores.put("IP", 0d);
            TaxonomyNodeDto bp = dto(nodes.get("BP"));
            bp.setChildren(IntStream.range(0, 33).mapToObj(i -> dto(nodes.get("BP-%03d".formatted(i)))).toList());
            TaxonomyNodeDto ip = dto(nodes.get("IP")); ip.setChildren(List.of(dto(nodes.get("IP-record"))));
            tree = List.of(bp, ip);
            when(catalogue.getFullTree()).thenReturn(tree);
            when(catalogue.getNodeByCode(anyString())).thenAnswer(call -> nodes.get(call.getArgument(0)));
            when(catalogue.getRootNodes()).thenReturn(List.of(nodes.get("IP")));
            when(catalogue.getChildrenOf(anyString())).thenAnswer(call -> call.getArgument(0).equals("IP")
                    ? List.of(nodes.get("IP-record")) : List.of());
            // Keep production context capture around the fixture's explicit scalar catalogue.
            // A missing capture must not be made acceptable by a production fallback.
            TaxonomyService relationCatalogue = new TaxonomyService(null, null, null) {
                @Override public TaxonomyNode getNodeByCode(String code) { return nodes.get(code); }
            };
            when(catalogue.getRelationAssessmentDescriptions(anyList())).thenAnswer(call ->
                    relationCatalogue.getRelationAssessmentDescriptions(call.getArgument(0)));
            when(llm.getActiveProviderName()).thenReturn("CUSTOM_OPENAI");
            when(llm.getActiveProviderId()).thenReturn(LlmProvider.CUSTOM_OPENAI.id());
            when(llm.supportsGenerativeCompletion()).thenReturn(true);
            when(llm.recoveryPolicyFingerprint(any())).thenReturn("reference-provider-and-prompts-v1");
            when(llm.analyzeWithBudget(anyString())).thenAnswer(call -> {
                String original = call.getArgument(0);
                var detail = AnalysisCheckpointSession.evaluate("root-and-children-reference", "CUSTOM_OPENAI",
                        List.copyOf(scores.keySet()), original, () -> {
                            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                            classifications.incrementAndGet();
                            var reply = new LlmCallDetail(); reply.setScores(scores);
                            reply.setReasons(Map.of("BP-032", "A separately requested low-share contribution"));
                            reply.setProvider("CUSTOM_OPENAI"); reply.setPrompt(original); reply.setRawResponse(json.writeValueAsString(scores));
                            return reply;
                        });
                var result = new AnalysisResult(detail.getScores(), tree);
                result.setReasons(detail.getReasons()); result.setStatus("SUCCESS"); return result;
            });
            when(llm.callLlmRaw(anyString(), any(LlmCompletionOptions.class))).thenAnswer(call -> answer(call.getArgument(0)));
            open(true);
            transaction(em -> {
                var project = new ArchitectureProject(PortfolioScope.key("owner", scope), scope.workspaceId(), "owner",
                        "REFERENCE", "Continuation reference", "", ProjectStatus.PLANNING, Instant.now());
                em.persist(project); em.flush(); projectId = project.getId();
                var requirement = new ProjectRequirement(project, "R1", "Read evidence", RequirementStatus.DRAFT,
                        1, Criticality.MEDIUM, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, "owner", Instant.now());
                em.persist(requirement); em.flush(); requirementId = requirement.getId();
                var version = version(requirement, 1); em.persist(version); em.flush();
                requirement.pointToVersion(version.getId(), Instant.now());
            });
        }

        private void open(boolean create) {
            Configuration configuration = new Configuration();
            configuration.getProperties().put("hibernate.session_factory.statement_inspector",
                    (org.hibernate.resource.jdbc.spi.StatementInspector) statement -> {
                        sql.add(statement);
                        return statement;
                    });
            for (Class<?> entity : List.of(ArchitectureProject.class, ProjectRequirement.class,
                    ProjectRequirementVersion.class, RequirementAnalysisJob.class, RequirementAnalysisJobItem.class,
                    RequirementAnalysisSnapshot.class, SnapshotReference.class,
                    RequirementElementMapping.class, RequirementRelationMapping.class,
                    AnalysisContinuationRun.class, AnalysisQuestionCheckpoint.class)) configuration.addAnnotatedClass(entity);
            factory = configuration.setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                    .setProperty("hibernate.connection.url", url).setProperty("hibernate.hbm2ddl.auto", create ? "create" : "validate")
                    .setProperty("hibernate.search.enabled", "false").buildSessionFactory();
            transactions = new JpaTransactionManager(factory);
            shared = SharedEntityManagerCreator.createSharedEntityManager(factory);
            var repositories = new JpaRepositoryFactory(shared);
            var projects = repositories.getRepository(ArchitectureProjectRepository.class);
            var requirements = repositories.getRepository(ProjectRequirementRepository.class);
            var versions = repositories.getRepository(ProjectRequirementVersionRepository.class);
            var jobs = repositories.getRepository(RequirementAnalysisJobRepository.class);
            var items = repositories.getRepository(RequirementAnalysisJobItemRepository.class);
            var snapshots = repositories.getRepository(RequirementAnalysisSnapshotRepository.class);
            var codec = new PortfolioJsonCodec(json);
            var fingerprints = mock(PortfolioFingerprintService.class);
            when(fingerprints.taxonomyFingerprint()).thenReturn("catalogue-v1");
            when(fingerprints.promptFingerprint()).thenReturn("prompts-v1");
            var projectTarget = new ProjectPortfolioService(projects, requirements, versions,
                    mock(ProjectSolutionRepository.class), mock(ProjectConflictRepository.class), fingerprints,
                    codec, mock(ReformulationPositiveReviewGuard.class));
            ReflectionTestUtils.setField(projectTarget, "entityManager", shared);
            var projectService = proxied(projectTarget);
            var bindings = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            bindings.registerSingleton("providerBinding", (ProjectProviderBinding) (projectId, targets, requested, username, context) ->
                    providerBinding.admittedProvider(projectId, targets, requested, username, context));
            var persistence = proxied(new PortfolioAnalysisPersistenceService(projects, requirements, versions, jobs, items,
                    snapshots, repositories.getRepository(RequirementElementMappingRepository.class),
                    repositories.getRepository(RequirementRelationMappingRepository.class), mock(AnalysisHypothesisLinkPort.class),
                    codec, new PortfolioSnapshotSelectionReader(shared, codec), new PortfolioSnapshotEvidenceReader(shared, codec),
                    bindings.getBeanProvider(ProjectProviderBinding.class)));
            var claims = proxied(new PortfolioAnalysisClaimPersistenceService(items, persistence, mock(HypothesisService.class)));
            var workQueue = proxied(new PortfolioAnalysisWorkQueue(jobs, items));
            var recovery = proxied(new PortfolioAnalysisRecoveryService(jobs, items, projectService));
            var budget = mock(AiPromptBudgetPolicy.class);
            var relations = new RequirementRelationSearchService(catalogue, new RelationCompatibilityMatrix(), llm, budget);
            // Production defaults intentionally stay 24 calls, 32 sources, batch 3 and 16k characters.
            var workspace = new UserWorkspace(); workspace.setUsername("owner"); workspace.setWorkspaceId(scope.workspaceId());
            var workspaces = mock(WorkspaceManager.class);
            when(workspaces.getWorkspaceById(scope.workspaceId())).thenReturn(workspace);
            var continuation = new AnalysisContinuationService(proxied(new AnalysisContinuationStore(shared, json)),
                    catalogue, llm, workspaces, json);
            ReflectionTestUtils.setField(continuation, "relations", relations);
            var local = proxied(new ProjectAnalysisContinuationService(continuation, workspaces, projectService, persistence, jobs, items,
                    requirements, snapshots, new PortfolioSnapshotEvidenceReader(shared, codec), transport));
            var projection = new RequirementArchitectureViewService(new com.taxonomy.architecture.pipeline.ArchitectureViewPipeline(
                    null, new com.taxonomy.architecture.pipeline.ArchitecturePipelineInvariantValidator()));
            var useCase = new AnalyzeRequirementUseCase(llm, budget, projection,
                    mock(AnalysisRelationGenerator.class), mock(HypothesisService.class), mock(WorkspaceViewContextReadPort.class),
                    () -> com.taxonomy.export.DiagramViewMetadata.fromConfig(com.taxonomy.export.DiagramSelectionConfig.trace(), "trace"));
            ReflectionTestUtils.setField(useCase, "requirementRelationSearch", relations);
            service = new ProjectRequirementAnalysisService(projectService, persistence, claims, workQueue, recovery, useCase,
                    mock(ArchitectureGapService.class), mock(ArchitecturePatternService.class), mock(ArchitectureRecommendationService.class),
                    fingerprints, llm, new TaskExecutorAdapter(queue::add), 100, 100, 900);
            service.budgetContinuations(local);
            jobControl = proxied(new CopilotJobControlService(jobs, persistence, shared));
            var policy = mock(AiAutomationPolicy.class);
            when(policy.costPolicy()).thenReturn(AiCostPolicy.METERED);
            copilot = new CopilotAutomationService(service, projectService, fingerprints, policy, new CopilotResultSelector(),
                    proxied(new CopilotResultPersistenceService(requirements, snapshots, items)), mock(CopilotCompletionService.class),
                    jobControl, mock(java.util.concurrent.ExecutorService.class));
        }

        private String answer(String prompt) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(prompt.length()).isLessThanOrEqualTo(16_000);
            prompts.add(prompt);
            var input = json.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
            String original = input.path("original").asString();
            if (input.has("nodes")) {
                assertThat(input.get("nodes").size()).isLessThanOrEqualTo(3);
                var selections = new ArrayList<Map<String, Object>>();
                for (var node : input.get("nodes")) {
                    String id = node.path("id").asString();
                    boolean explicit = explicitSources.contains(id);
                    boolean clarify = clarifySource && id.equals("BP-001");
                    selections.add(Map.of("nodeId", id, "outcome", clarify ? "UNRESOLVED" : explicit ? "EXPLICIT" : "REJECT",
                            "contributions", explicit ? List.of(Map.of("text", "read existing evidence", "quote", original, "condition", "")) : List.of(),
                            "rationale", explicit ? "This process reads the requested evidence" : "No distinct function is assigned to this context",
                            "question", clarify ? OPEN_QUESTION : ""));
                }
                return json.writeValueAsString(Map.of("selections", selections));
            }
            Query query = json.treeToValue(input, Query.class);
            assertThat(query.candidates()).hasSizeLessThanOrEqualTo(3);
            return json.writeValueAsString(Map.of("decisions", query.candidates().stream().map(node -> {
                if (clarifyRelation && query.contribution().source().id().equals("BP-000")
                        && query.type().equals("CONSUMES") && !node.container())
                    return new Decision(node.id(), Outcome.UNRESOLVED, "", "", null, "", "",
                            "The requirement does not define the evidence product's scope", OPEN_QUESTION);
                return new Decision(node.id(), !query.type().equals("CONSUMES") ? Outcome.REJECT
                            : query.phase() == Phase.VERIFY ? Outcome.VERIFIED : node.container() ? Outcome.DESCEND : Outcome.MATCH,
                            "existing evidence access", original, Necessity.REQUIRED, "", "", "Only existing evidence is read", "");
            }).toList()));
        }

        AnalysisJobView start(String key) { return service.analyzeProject(projectId,
                new AnalyzeProjectRequest(List.of(requirementId), false, "CUSTOM_OPENAI", 100, key), "owner", scope); }
        AnalysisJobView current(AnalysisJobView job) { return service.getJob(job.id(), projectId, "owner", scope); }
        SnapshotDetail snapshot(AnalysisJobView job) { return service.getSnapshot(projectId, job.items().getFirst().snapshotId(), "owner", scope); }
        AnalysisContinuationView eligibility(AnalysisJobView job) { return service.getContinuation(job.id(), projectId, job.items().getFirst().id(), "owner", scope); }
        AnalysisJobView continueWith(AnalysisJobView job, ContinueAnalysisRequest request) {
            return service.enqueueContinuation(job.id(), projectId, job.items().getFirst().id(), request, "owner", scope);
        }
        void runQueued() { while (!queue.isEmpty()) queue.remove().run(); }
        String questionEvidence(String run, String question) { return questionColumn(run, question, "detailJson"); }
        String questionState(String run, String question) { return questionColumn(run, question, "state"); }
        private String questionColumn(String run, String question, String column) {
            return new TransactionTemplate(transactions).execute(status -> shared.createQuery(
                            "select q." + column + " from AnalysisQuestionCheckpoint q where q.run.id=:run and q.questionKey=:question", String.class)
                    .setParameter("run", run).setParameter("question", question).getSingleResult());
        }
        void transaction(Consumer<EntityManager> operation) { new TransactionTemplate(transactions).executeWithoutResult(status -> operation.accept(shared)); }
        ProjectRequirementVersion version(ProjectRequirement requirement, int number) {
            String text = number == 1 ? TEXT : TEXT + " The archive retains access history.";
            String hash;
            try { hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            return new ProjectRequirementVersion(requirement, number, text, hash, "reference", "owner", Instant.now(),
                    null, null, null, null, null, null);
        }
        @SuppressWarnings("unchecked") <T> T proxied(T target) {
            var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            return (T) proxy.getProxy();
        }
        void node(String id, String root, String parent) {
            var node = new TaxonomyNode(); node.setCode(id); node.setNameEn(id); node.setDescriptionEn("Reference evidence function " + id);
            node.setTaxonomyRoot(root); node.setParentCode(parent); node.setLevel(parent == null ? 0 : 1); nodes.put(id, node);
        }
        TaxonomyNodeDto dto(TaxonomyNode node) {
            var dto = new TaxonomyNodeDto(); dto.setCode(node.getCode()); dto.setNameEn(node.getNameEn());
            dto.setDescriptionEn(node.getDescriptionEn()); dto.setTaxonomyRoot(node.getTaxonomyRoot());
            dto.setParentCode(node.getParentCode()); dto.setLevel(node.getLevel()); return dto;
        }
        void restart() throws Exception { close(); queue.clear(); open(false); }
        @Override public void close() throws Exception {
            factory.close();
            try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
    }
}
