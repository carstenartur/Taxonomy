package com.taxonomy.portfolio.service;

import com.taxonomy.dto.*;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.reformulation.ReformulationPositiveReviewGuard;
import com.taxonomy.portfolio.repository.*;
import com.taxonomy.relations.service.AnalysisHypothesisLinkPort;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real read and finalization paths with composite foreign keys and large, valid payloads. */
class PortfolioSnapshotPayloadReadTest {
    @Test
    void savingMappingsKeepsTheManagedSnapshotWithoutReadingItsFourPayloadsAgain() {
        try (var f = new Fixture()) {
            var source = f.persistence.getSnapshot(f.projectId, f.snapshotIds.getFirst(), "owner", f.scope);
            var architecture = new RequirementArchitectureView();
            architecture.setIncludedElements(java.util.stream.IntStream.range(0, 12).mapToObj(index -> {
                var element = new RequirementElementView();
                element.setNodeCode("BP-" + index); element.setTitle("Capability " + index);
                element.setTaxonomySheet("BP"); element.setDirectLlmScore(80);
                element.setRelevance(.8); element.setOrigin(NodeOrigin.DIRECT_SCORED);
                element.setPresenceReason("Published requirement evidence " + index);
                return element;
            }).toList());
            architecture.setIncludedRelationships(java.util.stream.IntStream.range(0, 7).mapToObj(index -> {
                var relation = new RequirementRelationshipView();
                relation.setSourceCode("BP-" + index); relation.setTargetCode("CP-" + index);
                relation.setRelationType("USES"); relation.setRelationCategory("STRUCTURAL");
                relation.setPropagatedRelevance(.8); relation.setConfidence(.9);
                relation.setPresenceReason("Published relation evidence " + index);
                return relation;
            }).toList());
            var analysis = source.analysis();
            analysis.setStatus("PARTIAL");
            analysis.setArchitectureView(architecture);
            String snapshotId = UUID.randomUUID().toString();
            Long itemId = new TransactionTemplate(f.transactions).execute(status -> f.shared.createQuery(
                    "select item.id from RequirementAnalysisJobItem item where item.jobId = :job", Long.class)
                    .setParameter("job", f.jobIds.getFirst()).getSingleResult());

            f.resetReads();
            var summary = f.persistence.persistSnapshot(itemId, f.jobIds.getFirst(), f.projectId,
                    PortfolioScope.key("owner", f.scope), snapshotId, "saved-session", analysis,
                    source.gapAnalysis(), source.patternDetection(), source.recommendation(),
                    "CUSTOM_OPENAI", "model", "p".repeat(64), "t".repeat(64), "owner", f.scope, 123);

            // Assigned IDs require one initial merge lookup of the new, absent ID. The mappings
            // must use its managed return, rather than load all four CLOBs in a transient FK check.
            assertThat(f.payloadSelects().stream().filter(sql -> sql.startsWith("select")).toList())
                    .as("Only the initial lookup of the new snapshot ID")
                    .hasSize(1);
            assertThat(summary.id()).isEqualTo(snapshotId);
            var saved = f.persistence.getSnapshot(f.projectId, snapshotId, "owner", f.scope);
            assertThat(saved.elementMappings()).hasSize(12).allSatisfy(mapping ->
                    assertThat(mapping.snapshotId()).isEqualTo(snapshotId));
            assertThat(saved.relationMappings()).hasSize(7).allSatisfy(mapping ->
                    assertThat(mapping.snapshotId()).isEqualTo(snapshotId));
            var json = JsonMapper.builder().build();
            var expected = List.of(analysis, source.gapAnalysis(), source.patternDetection(), source.recommendation());
            var actual = List.of(saved.analysis(), saved.gapAnalysis(), saved.patternDetection(), saved.recommendation());
            for (int payload = 0; payload < expected.size(); payload++) {
                tools.jackson.databind.JsonNode expectedJson = json.valueToTree(expected.get(payload));
                tools.jackson.databind.JsonNode actualJson = json.valueToTree(actual.get(payload));
                assertThat(actualJson).as("Complete immutable payload %s", payload).isEqualTo(expectedJson);
            }
            new TransactionTemplate(f.transactions).executeWithoutResult(status -> {
                for (String entity : List.of("RequirementElementMapping", "RequirementRelationMapping"))
                    assertThat(f.shared.createQuery("select count(mapping) from " + entity
                                    + " mapping where mapping.snapshotId = :snapshot and mapping.scopeKey = :scope", Long.class)
                            .setParameter("snapshot", snapshotId).setParameter("scope", PortfolioScope.key("owner", f.scope))
                            .getSingleResult()).isEqualTo(entity.equals("RequirementElementMapping") ? 12L : 7L);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void completeSnapshotReadsEachPayloadOnceAndKeepsHistoricalIdentityAndMappingDecisions(int pass) {
        try (var f = new Fixture()) {
            var detail = f.persistence.getSnapshot(f.projectId, f.snapshotIds.get(pass), "owner", f.scope);
            assertThat(detail.summary().id()).isEqualTo(f.snapshotIds.get(pass));
            assertThat(detail.summary().projectId()).isEqualTo(f.projectId);
            assertThat(detail.summary().requirementId()).isEqualTo(f.requirementId);
            assertThat(detail.summary().requirementKey()).isEqualTo("REQ-1");
            assertThat(detail.summary().requirementVersionId()).isEqualTo(f.versionId);
            assertThat(detail.summary().requirementVersionNumber()).isEqualTo(1);
            assertThat(detail.summary().jobId()).isEqualTo(f.jobIds.get(pass));
            assertThat(detail.summary().provider()).isEqualTo("CUSTOM_OPENAI");
            assertThat(detail.analysis().getScores()).containsExactlyEntriesOf(Map.of("BP-1000", 50d + pass * 25d));
            assertThat(detail.analysis().getTree().getFirst().getDescriptionEn()).isEqualTo(Fixture.LARGE_EVIDENCE);
            assertThat(detail.analysis().getReasons()).containsEntry("BP-1000", "Reason for pass " + pass);
            assertThat(detail.gapAnalysis().getNotes()).containsExactly(Fixture.LARGE_EVIDENCE);
            assertThat(detail.patternDetection().getNotes()).containsExactly(Fixture.LARGE_EVIDENCE);
            assertThat(detail.recommendation().getReasoning()).containsExactly(Fixture.LARGE_EVIDENCE);
            assertThat(detail.elementMappings()).singleElement().satisfies(mapping -> {
                assertThat(mapping.snapshotId()).isEqualTo(f.snapshotIds.get(pass));
                assertThat(mapping.nodeCode()).isEqualTo("BP-1000");
                assertThat(mapping.reviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
                assertThat(mapping.actionStatus()).isEqualTo(ActionStatus.REUSE);
                assertThat(mapping.actionEvidence()).isEqualTo("Existing capability verified");
                assertThat(mapping.decisionBy()).isEqualTo("reviewer");
                assertThat(mapping.decisionAt()).isEqualTo(Fixture.TIME);
                assertThat(mapping.decisionComment()).isEqualTo("Approved mapping");
            });
            assertThat(detail.relationMappings()).singleElement().satisfies(mapping -> {
                assertThat(mapping.snapshotId()).isEqualTo(f.snapshotIds.get(pass));
                assertThat(mapping.sourceCode()).isEqualTo("BP-1000");
                assertThat(mapping.targetCode()).isEqualTo("CP-1041");
                assertThat(mapping.relationType()).isEqualTo("USES");
                assertThat(mapping.reviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
                assertThat(mapping.decisionBy()).isEqualTo("reviewer");
                assertThat(mapping.decisionAt()).isEqualTo(Fixture.TIME);
                assertThat(mapping.decisionComment()).isEqualTo("Approved relation");
            });
            assertThat(f.payloadSelects()).as("A complete snapshot needs one SELECT of its four payloads")
                    .singleElement().satisfies(sql -> assertThat(sql).contains("analysis_payload", "gap_payload",
                            "pattern_payload", "recommendation_payload"));
            f.assertNoResultEntityLoads();
        }
    }

    @Test
    void terminalAndRepeatedCopilotReadsOnlyTheAnalysisPayloadNeededToRankEachPass() {
        try (var f = new Fixture()) {
            for (int read = 0; read < 2; read++) {
                f.resetReads();
                var operation = f.copilot.getOperation(f.projectId, Fixture.OPERATION, "owner", f.scope);
                assertThat(operation.status()).isEqualTo(AnalysisStatus.PARTIAL);
                assertThat(operation.jobs()).extracting(AnalysisJobView::id).containsExactlyElementsOf(f.jobIds);
                assertThat(operation.selectedSnapshotId()).isEqualTo(f.snapshotIds.get(1));
                assertThat(f.payloadSelects()).as("One analysis payload per immutable pass, including repeated terminal polls")
                        .hasSize(2).allSatisfy(sql -> assertThat(sql)
                                .doesNotContain("gap_payload", "pattern_payload", "recommendation_payload"));
                f.assertNoResultEntityLoads();
                assertThat(f.sql).noneMatch(sql -> sql.startsWith("update ") || sql.startsWith("insert "));
            }
            // The worker already points at its latest snapshot before the first finalization.
            // Equal pointers alone therefore cannot be used to suppress the first enrichment.
            verify(f.completion, times(2)).enrich(f.projectId, "owner", f.scope, false, false);
        }
    }

    @Test
    void completeSnapshotAvoidsFullClobStringsWithoutLosingAnyPayloadFields() {
        try (var f = new Fixture()) {
            var allocation = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            assertThat(allocation.isThreadAllocatedMemorySupported()).isTrue();
            allocation.setThreadAllocatedMemoryEnabled(true);
            String snapshotId = f.snapshotIds.getFirst();
            Supplier<List<Object>> materialized = () -> new TransactionTemplate(f.transactions).execute(status -> {
                Object[] row = f.shared.createQuery("""
                        select snapshot.analysisPayload, snapshot.gapAnalysisPayload,
                               snapshot.patternDetectionPayload, snapshot.recommendationPayload
                          from RequirementAnalysisSnapshot snapshot
                         where snapshot.id = :id and snapshot.projectId = :project and snapshot.scopeKey = :scope
                        """, Object[].class).setParameter("id", snapshotId).setParameter("project", f.projectId)
                        .setParameter("scope", PortfolioScope.key("owner", f.scope)).getSingleResult();
                return List.of(f.codec.read((String) row[0], AnalysisResult.class),
                        f.codec.read((String) row[1], GapAnalysisView.class),
                        f.codec.read((String) row[2], PatternDetectionView.class),
                        f.codec.read((String) row[3], ArchitectureRecommendation.class));
            });
            Supplier<SnapshotDetail> complete = () -> f.persistence.getSnapshot(f.projectId, snapshotId, "owner", f.scope);
            for (int warm = 0; warm < 3; warm++) { materialized.get(); complete.get(); }
            long materializedBytes = 0, completeBytes = 0;
            for (int repetition = 0; repetition < 3; repetition++) {
                long before = allocation.getCurrentThreadAllocatedBytes();
                var original = materialized.get();
                materializedBytes += allocation.getCurrentThreadAllocatedBytes() - before;
                before = allocation.getCurrentThreadAllocatedBytes();
                var detail = complete.get();
                completeBytes += allocation.getCurrentThreadAllocatedBytes() - before;
                var actual = List.of(detail.analysis(), detail.gapAnalysis(), detail.patternDetection(), detail.recommendation());
                var json = JsonMapper.builder().build();
                for (int payload = 0; payload < original.size(); payload++) {
                    tools.jackson.databind.JsonNode actualFields = json.valueToTree(actual.get(payload));
                    tools.jackson.databind.JsonNode originalFields = json.valueToTree(original.get(payload));
                    assertThat(actualFields).isEqualTo(originalFields);
                }
            }
            assertThat(completeBytes).as("Full evidence must avoid CLOB String materialization: %s vs baseline %s allocated bytes",
                            completeBytes, materializedBytes)
                    .isLessThan((long) (materializedBytes * .80));
        }
    }

    @Test
    void fullEvidenceAndContinuationAnalysisRejectEveryForeignOwnershipColumn() {
        try (var f = new Fixture()) {
            record Identity(Long projectId, String snapshotId, String scopeKey, String jobId,
                            Long requirementId, Long requirementVersionId) { }
            String snapshotId = f.snapshotIds.getFirst(), tenant = PortfolioScope.key("owner", f.scope);
            var foreign = List.of(
                    new Identity(f.otherProjectId, snapshotId, tenant, f.jobIds.getFirst(), f.requirementId, f.versionId),
                    new Identity(f.projectId, UUID.randomUUID().toString(), tenant, f.jobIds.getFirst(), f.requirementId, f.versionId),
                    new Identity(f.projectId, snapshotId, "foreign-scope", f.jobIds.getFirst(), f.requirementId, f.versionId),
                    new Identity(f.projectId, snapshotId, tenant, f.jobIds.getLast(), f.requirementId, f.versionId),
                    new Identity(f.projectId, snapshotId, tenant, f.jobIds.getFirst(), f.requirementId + 100, f.versionId),
                    new Identity(f.projectId, snapshotId, tenant, f.jobIds.getFirst(), f.requirementId, f.versionId + 100));
            f.transaction(em -> {
                var reader = new PortfolioSnapshotEvidenceReader(em, f.codec);
                var full = reader.read(f.projectId, snapshotId, tenant).orElseThrow();
                var analysis = reader.readAnalysis(f.projectId, snapshotId, tenant, f.jobIds.getFirst(), f.requirementId,
                        f.versionId).orElseThrow().analysis();
                assertThat(analysis.getReasons()).isEqualTo(full.analysis().getReasons());
                assertThat(analysis.getTree().getFirst().getDescriptionEn()).isEqualTo(Fixture.LARGE_EVIDENCE);
                for (int i = 0; i < foreign.size(); i++) {
                    var identity = foreign.get(i);
                    assertThat(reader.readAnalysis(identity.projectId(), identity.snapshotId(), identity.scopeKey(), identity.jobId(),
                            identity.requirementId(), identity.requirementVersionId())).isEmpty();
                    if (i < 3) assertThat(reader.read(identity.projectId(), identity.snapshotId(), identity.scopeKey())).isEmpty();
                }
            });
            f.assertNoResultEntityLoads();
        }
    }

    @Test
    void selectionAvoidsFullClobStringAllocationWhileReadingTheSameScoresAndWarnings() {
        try (var f = new Fixture()) {
            var allocation = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            assertThat(allocation.isThreadAllocatedMemorySupported()).isTrue();
            allocation.setThreadAllocatedMemoryEnabled(true);
            String snapshotId = f.snapshotIds.getFirst();
            Supplier<AnalysisResult> materialized = () -> new TransactionTemplate(f.transactions).execute(status -> {
                String json = f.shared.createQuery("""
                        select snapshot.analysisPayload from RequirementAnalysisSnapshot snapshot
                         where snapshot.id = :id and snapshot.projectId = :project and snapshot.scopeKey = :scope
                        """, String.class).setParameter("id", snapshotId).setParameter("project", f.projectId)
                        .setParameter("scope", PortfolioScope.key("owner", f.scope)).getSingleResult();
                return f.codec.readSelectionAnalysis(json);
            });
            Supplier<com.taxonomy.portfolio.dto.SnapshotSelection> selection = () ->
                    f.persistence.getSnapshotSelection(f.projectId, snapshotId, "owner", f.scope);
            for (int warm = 0; warm < 3; warm++) { materialized.get(); selection.get(); }
            long materializedBytes = 0, selectionBytes = 0;
            for (int repetition = 0; repetition < 3; repetition++) {
                long before = allocation.getCurrentThreadAllocatedBytes();
                var full = materialized.get();
                materializedBytes += allocation.getCurrentThreadAllocatedBytes() - before;
                before = allocation.getCurrentThreadAllocatedBytes();
                var selected = selection.get();
                selectionBytes += allocation.getCurrentThreadAllocatedBytes() - before;
                assertThat(selected.scores()).containsExactlyEntriesOf(full.getScores());
                assertThat(selected.warningCount()).isEqualTo(full.getWarnings().size());
                assertThat(selected.elementMappingCount()).isEqualTo(1);
                assertThat(selected.relationMappingCount()).isEqualTo(1);
            }
            assertThat(selectionBytes).as("Selection must avoid full CLOB String materialization: %s vs baseline %s allocated bytes",
                            selectionBytes, materializedBytes)
                    .isLessThan((long) (materializedBytes * .80));
        }
    }

    @Test
    void snapshotSelectionUsesCurrentScopedMappingCountsAndRejectsForeignIdentity() {
        try (var f = new Fixture()) {
            String snapshotId = f.snapshotIds.getFirst();
            var initial = f.persistence.getSnapshotSelection(f.projectId, snapshotId, "owner", f.scope);
            assertThat(initial.elementMappingCount()).isEqualTo(1);
            assertThat(initial.relationMappingCount()).isEqualTo(1);
            f.transaction(em -> {
                var snapshot = em.find(RequirementAnalysisSnapshot.class, snapshotId);
                em.persist(new RequirementElementMapping(snapshot, "BP-1001", "Additional decision", "BP", 10, .1, .8,
                        MappingOrigin.DIRECT, "BP/BP-1001", "Current reviewed mapping", true));
                em.persist(new RequirementRelationMapping(snapshot, "BP-1001", "CP-1041", "USES", "LLM_SUPPORTED",
                        "STRUCTURAL", .1, .8, "Current reviewed relation"));
            });
            f.resetReads();
            var updated = f.persistence.getSnapshotSelection(f.projectId, snapshotId, "owner", f.scope);
            assertThat(updated.elementMappingCount()).isEqualTo(2);
            assertThat(updated.relationMappingCount()).isEqualTo(2);
            assertThat(updated.scores()).containsExactlyEntriesOf(initial.scores());
            assertThat(f.payloadSelects()).singleElement().satisfies(sql -> assertThat(sql)
                    .doesNotContain("gap_payload", "pattern_payload", "recommendation_payload"));
            f.assertNoResultEntityLoads();
            var otherScope = new WorkspaceContext("owner", "other-workspace", "draft", "repository");
            assertThatThrownBy(() -> f.persistence.getSnapshotSelection(f.otherProjectId, snapshotId, "owner", f.scope))
                    .isInstanceOfSatisfying(PortfolioException.class, failure ->
                            assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
            assertThatThrownBy(() -> f.persistence.getSnapshotSelection(f.projectId, snapshotId, "owner", otherScope))
                    .isInstanceOfSatisfying(PortfolioException.class, failure ->
                            assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
        }
    }

    @Test
    void realSolutionAndProductEnrichmentDoesNotReloadSnapshotsWhenNoCoverageCanProduceAProposal() {
        try (var f = new Fixture(true)) {
            for (int read = 0; read < 2; read++) {
                f.resetReads();
                var operation = f.copilot.getOperation(f.projectId, Fixture.OPERATION, "owner", f.scope);
                assertThat(operation.selectedSnapshotId()).isEqualTo(f.snapshotIds.get(1));
                verify(f.coverage, times(read + 1)).findByNodeCode("BP-1000");
                assertThat(f.payloadSelects()).as("Real enrichment must not hydrate snapshots just to check catalogue coverage")
                        .hasSize(2).allSatisfy(sql -> assertThat(sql).doesNotContain("gap_payload", "pattern_payload", "recommendation_payload"));
                f.assertNoResultEntityLoads();
                assertThat(f.sql).noneMatch(sql -> sql.startsWith("insert ") || sql.startsWith("update "));
            }
        }
    }

    @Test
    void fullSnapshotCannotBeReadFromAnotherProjectOrWorkspace() {
        try (var f = new Fixture()) {
            var foreignScope = new WorkspaceContext("owner", "other-workspace", "draft", "repository");
            assertThatThrownBy(() -> f.persistence.getSnapshot(f.otherProjectId, f.snapshotIds.getFirst(), "owner", f.scope))
                    .isInstanceOfSatisfying(PortfolioException.class, failure ->
                            assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
            assertThatThrownBy(() -> f.persistence.getSnapshot(f.projectId, f.snapshotIds.getFirst(), "owner", foreignScope))
                    .isInstanceOfSatisfying(PortfolioException.class, failure ->
                            assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
            f.assertNoResultEntityLoads();
        }
    }

    @Test
    void terminalSelectionStillRejectsAChangedCurrentRequirementVersion() {
        try (var f = new Fixture()) {
            f.transaction(em -> {
                var requirement = em.find(ProjectRequirement.class, f.requirementId);
                var version = new ProjectRequirementVersion(requirement, 2, "Changed source text", "b".repeat(64),
                        "User revision", "owner", Fixture.TIME.plusSeconds(20), null, null, null, null, null, null);
                em.persist(version); em.flush();
                requirement.pointToVersion(version.getId(), Fixture.TIME.plusSeconds(20));
            });
            f.resetReads();
            assertThatThrownBy(() -> f.copilot.getOperation(f.projectId, Fixture.OPERATION, "owner", f.scope))
                    .isInstanceOfSatisfying(PortfolioException.class, failure ->
                            assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.CONFLICT));
            verifyNoInteractions(f.completion);
            assertThat(f.sql).noneMatch(sql -> sql.startsWith("update "));
        }
    }

    @Test
    void anExplicitContinuationIsNotFinalizedWhilePendingAndItsNewSnapshotIsRankedOnCompletion() {
        try (var f = new Fixture()) {
            assertThat(f.copilot.getOperation(f.projectId, Fixture.OPERATION, "owner", f.scope).selectedSnapshotId())
                    .isEqualTo(f.snapshotIds.get(1));
            f.transaction(em -> {
                var job = em.find(RequirementAnalysisJob.class, f.jobIds.getFirst());
                var item = em.createQuery("from RequirementAnalysisJobItem i where i.jobId = :id", RequirementAnalysisJobItem.class)
                        .setParameter("id", job.getId()).getSingleResult();
                item.prepareBudgetContinuation(); job.markPending();
            });
            reset(f.completion); f.resetReads();
            var pending = f.copilot.getOperation(f.projectId, Fixture.OPERATION, "owner", f.scope);
            assertThat(pending.jobs().getFirst().status()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(pending.selectedSnapshotId()).isEqualTo(f.snapshotIds.get(1));
            assertThat(f.payloadSelects()).isEmpty();
            verifyNoInteractions(f.completion);

            String continuedId = UUID.randomUUID().toString();
            f.transaction(em -> {
                var job = em.find(RequirementAnalysisJob.class, f.jobIds.getFirst());
                var requirement = em.find(ProjectRequirement.class, f.requirementId);
                var version = em.find(ProjectRequirementVersion.class, f.versionId);
                var analysis = new AnalysisResult(Map.of("BP-1000", 90d), List.of());
                var continued = new RequirementAnalysisSnapshot(continuedId, em.find(ArchitectureProject.class, f.projectId),
                        requirement, version, job, AnalysisStatus.PARTIAL, "continued-source", "CUSTOM_OPENAI", "model",
                        "p".repeat(64), "t".repeat(64), f.scope.workspaceId(), f.scope.currentBranch(), "c".repeat(40), "owner",
                        Fixture.TIME.plusSeconds(30), 200L, 0, "CALL_BUDGET", f.codec.write(analysis), null, null, null);
                em.persist(continued); em.flush();
                var item = em.createQuery("from RequirementAnalysisJobItem i where i.jobId = :id", RequirementAnalysisJobItem.class)
                        .setParameter("id", job.getId()).getSingleResult();
                item.complete(AnalysisStatus.PARTIAL, continuedId, Fixture.TIME.plusSeconds(31), "CALL_BUDGET");
                job.complete(0, 1, 0, "CALL_BUDGET", Fixture.TIME.plusSeconds(31));
                requirement.pointToAnalysis(continuedId, Fixture.TIME.plusSeconds(31));
            });
            f.resetReads();
            var completed = f.copilot.getOperation(f.projectId, Fixture.OPERATION, "owner", f.scope);
            assertThat(completed.selectedSnapshotId()).isEqualTo(continuedId);
            assertThat(completed.jobs().getFirst().items().getFirst().requirementVersionId()).isEqualTo(f.versionId);
            assertThat(f.payloadSelects()).hasSize(2).allSatisfy(sql -> assertThat(sql).doesNotContain("gap_payload"));
            verify(f.completion).enrich(f.projectId, "owner", f.scope, false, false);
            assertThat(f.persistence.getSnapshot(f.projectId, f.snapshotIds.getFirst(), "owner", f.scope).analysis().getScores())
                    .containsEntry("BP-1000", 50d);
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"0,PARTIAL", "1,PARTIAL", "0,PENDING", "1,RUNNING"})
    void staleFinalizerCannotPromoteAnArchivedWinnerAfterEitherPassContinues(int pass, AnalysisStatus nextState) {
        try (var f = new Fixture()) {
            String continuedId = UUID.randomUUID().toString();
            doAnswer(invocation -> {
                Object ranked = invocation.callRealMethod();
                // The old finalizer has read/ranked both immutable snapshots. A new
                // generation commits before it reaches the promotion transaction.
                f.transaction(em -> {
                    var item = em.createQuery("from RequirementAnalysisJobItem i where i.jobId = :id", RequirementAnalysisJobItem.class)
                            .setParameter("id", f.jobIds.get(pass)).getSingleResult();
                    item.prepareBudgetContinuation(); item.getJob().markPending();
                    if (nextState == AnalysisStatus.RUNNING) {
                        item.markRunning(Fixture.TIME.plusSeconds(20)); item.getJob().markRunning(Fixture.TIME.plusSeconds(20));
                    }
                });
                if (nextState == AnalysisStatus.PARTIAL) f.transaction(em -> {
                    var job = em.find(RequirementAnalysisJob.class, f.jobIds.get(pass));
                    var requirement = em.find(ProjectRequirement.class, f.requirementId);
                    var analysis = new AnalysisResult(Map.of("BP-1000", 90d), List.of());
                    em.persist(new RequirementAnalysisSnapshot(continuedId, em.find(ArchitectureProject.class, f.projectId),
                            requirement, em.find(ProjectRequirementVersion.class, f.versionId), job, AnalysisStatus.PARTIAL,
                            "continued-source", "CUSTOM_OPENAI", "model", "p".repeat(64), "t".repeat(64),
                            f.scope.workspaceId(), f.scope.currentBranch(), "c".repeat(40), "owner", Fixture.TIME.plusSeconds(30),
                            200L, 0, "CALL_BUDGET", f.codec.write(analysis), null, null, null));
                    var item = em.createQuery("from RequirementAnalysisJobItem i where i.jobId = :id", RequirementAnalysisJobItem.class)
                            .setParameter("id", job.getId()).getSingleResult();
                    item.complete(AnalysisStatus.PARTIAL, continuedId, Fixture.TIME.plusSeconds(31), "CALL_BUDGET");
                    job.complete(0, 1, 0, "CALL_BUDGET", Fixture.TIME.plusSeconds(31));
                    requirement.pointToAnalysis(continuedId, Fixture.TIME.plusSeconds(31));
                });
                return ranked;
            }).when(f.selector).selectForOperation(anyList());
            f.copilot.getOperation(f.projectId, Fixture.OPERATION, "owner", f.scope);
            assertThat(f.projects.getRequirement(f.projectId, f.requirementId, "owner", f.scope).currentAnalysisSnapshotId())
                    .as("An older terminal-status read must not restore its archived winner")
                    .isEqualTo(nextState == AnalysisStatus.PARTIAL ? continuedId : f.snapshotIds.get(1));
            verifyNoInteractions(f.completion);
            doCallRealMethod().when(f.selector).selectForOperation(anyList());
            var fresh = f.copilot.getOperation(f.projectId, Fixture.OPERATION, "owner", f.scope);
            if (nextState == AnalysisStatus.PARTIAL) {
                assertThat(fresh.selectedSnapshotId()).isEqualTo(continuedId);
                verify(f.completion).enrich(f.projectId, "owner", f.scope, false, false);
            } else {
                assertThat(fresh.status()).isEqualTo(nextState);
                verifyNoInteractions(f.completion);
            }
        }
    }

    @Test
    void anActualPointerChangeStillUsesTheManagedVersionedMutation() {
        try (var f = new Fixture()) {
            f.transaction(em -> em.find(ProjectRequirement.class, f.requirementId)
                    .pointToAnalysis(f.snapshotIds.getFirst(), Fixture.TIME));
            long[] before = {0};
            f.transaction(em -> before[0] = em.createQuery("select r.rowVersion from ProjectRequirement r where r.id = :id", Long.class)
                    .setParameter("id", f.requirementId).getSingleResult());
            f.resetReads();
            assertThat(f.selection.selectCurrentSnapshot(f.projectId, f.requirementId, f.snapshotIds.get(1), "owner", f.scope))
                    .isEqualTo(f.snapshotIds.get(1));
            assertThat(f.sql).anyMatch(sql -> sql.startsWith("update project_requirement ") && sql.contains("row_version=?"));
            assertThat(f.projects.getRequirement(f.projectId, f.requirementId, "owner", f.scope).currentAnalysisSnapshotId())
                    .isEqualTo(f.snapshotIds.get(1));
            f.transaction(em -> assertThat(em.createQuery("select r.rowVersion from ProjectRequirement r where r.id = :id", Long.class)
                    .setParameter("id", f.requirementId).getSingleResult()).isEqualTo(before[0] + 1));
        }
    }

    @Test
    void unchangedSelectionStillLocksTheRequirementAgainstAConcurrentVersionChange() throws Exception {
        try (var f = new Fixture()) {
            var executor = Executors.newFixedThreadPool(2, task -> {
                var worker = new Thread(task, "snapshot-selection-lock-test");
                worker.setDaemon(true);
                return worker;
            });
            java.util.concurrent.Future<?> mutating = null;
            var lockHeld = new CountDownLatch(1);
            var releaseLock = new CountDownLatch(1);
            var mutationStarted = new CountDownLatch(1);
            long[] nextVersion = {0};
            f.transaction(em -> {
                var version = new ProjectRequirementVersion(em.find(ProjectRequirement.class, f.requirementId), 2,
                        "New source text", "b".repeat(64), "Concurrent revision", "owner", Fixture.TIME,
                        null, null, null, null, null, null);
                em.persist(version); em.flush(); nextVersion[0] = version.getId();
            });
            f.resetReads();
            var selecting = executor.submit(() -> f.transaction(em -> {
                f.selection.selectCurrentSnapshot(f.projectId, f.requirementId, f.snapshotIds.get(1), "owner", f.scope);
                lockHeld.countDown();
                try {
                    if (!releaseLock.await(10, TimeUnit.SECONDS)) throw new AssertionError("Selection lock was not released");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            }));
            try {
                assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(f.sql).anyMatch(sql -> sql.contains("current_snapshot_id") && sql.endsWith(" for update"));
                f.assertNoResultEntityLoads();
                var mutation = executor.submit(() -> f.transaction(em -> {
                    mutationStarted.countDown();
                    var requirements = new JpaRepositoryFactory(em).getRepository(ProjectRequirementRepository.class);
                    var requirement = requirements.findByIdAndProjectIdAndScopeKeyForUpdate(f.requirementId, f.projectId,
                            PortfolioScope.key("owner", f.scope)).orElseThrow();
                    requirement.pointToVersion(nextVersion[0], Fixture.TIME.plusSeconds(50));
                }));
                mutating = mutation;
                assertThat(mutationStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> mutation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseLock.countDown();
                selecting.get(10, TimeUnit.SECONDS); mutation.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> f.selection.selectCurrentSnapshot(f.projectId, f.requirementId, f.snapshotIds.get(1), "owner", f.scope))
                        .isInstanceOfSatisfying(PortfolioException.class, failure ->
                                assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.CONFLICT));
            } finally {
                releaseLock.countDown();
                selecting.cancel(true);
                if (mutating != null) mutating.cancel(true);
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                        .as("Locking test workers must terminate before fixture teardown").isTrue();
            }
        }
    }

    static final class Fixture implements AutoCloseable {
        static final Instant TIME = Instant.parse("2026-10-17T06:00:00Z");
        static final String OPERATION = "a".repeat(64);
        static final String LARGE_EVIDENCE = "Published catalogue evidence. ".repeat(36_000);
        final WorkspaceContext scope = new WorkspaceContext("owner", "owned-workspace", "draft", "repository");
        final List<String> sql = new CopyOnWriteArrayList<>();
        final SessionFactory factory;
        final JpaTransactionManager transactions;
        final EntityManager shared;
        final PortfolioJsonCodec codec = new PortfolioJsonCodec(JsonMapper.builder().build());
        final ProjectPortfolioService projects;
        final PortfolioAnalysisPersistenceService persistence;
        final CopilotResultPersistenceService selection;
        final CopilotCompletionService completion;
        final CopilotResultSelector selector = spy(new CopilotResultSelector());
        final SolutionTaxonomyCoverageRepository coverage = mock(SolutionTaxonomyCoverageRepository.class);
        final boolean realEnrichment;
        final CopilotAutomationService copilot;
        final List<String> jobIds = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        final List<String> snapshotIds = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        Long projectId, otherProjectId, requirementId, versionId;

        Fixture() { this(false); }

        Fixture(boolean realEnrichment) {
            this.realEnrichment = realEnrichment;
            Configuration configuration = new Configuration();
            for (Class<?> entity : List.of(ArchitectureProject.class, ProjectRequirement.class,
                    ProjectRequirementVersion.class, RequirementAnalysisJob.class, RequirementAnalysisJobItem.class,
                    RequirementAnalysisSnapshot.class, SnapshotReference.class,
                    RequirementElementMapping.class, RequirementRelationMapping.class))
                configuration.addAnnotatedClass(entity);
            configuration.getProperties().put("hibernate.session_factory.statement_inspector", (StatementInspector) statement -> {
                sql.add(statement); return statement;
            });
            factory = configuration.setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                    .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:snapshot-read-" + UUID.randomUUID() + ";shutdown=true")
                    .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .setProperty("hibernate.generate_statistics", "true")
                    .setProperty("hibernate.search.enabled", "false").buildSessionFactory();
            transactions = new JpaTransactionManager(factory);
            shared = SharedEntityManagerCreator.createSharedEntityManager(factory);
            var repositories = new JpaRepositoryFactory(shared);
            var projectRepository = repositories.getRepository(ArchitectureProjectRepository.class);
            var requirements = repositories.getRepository(ProjectRequirementRepository.class);
            var versions = repositories.getRepository(ProjectRequirementVersionRepository.class);
            var jobs = repositories.getRepository(RequirementAnalysisJobRepository.class);
            var items = repositories.getRepository(RequirementAnalysisJobItemRepository.class);
            var snapshots = repositories.getRepository(RequirementAnalysisSnapshotRepository.class);
            var fingerprints = mock(PortfolioFingerprintService.class);
            projects = proxy(new ProjectPortfolioService(projectRepository, requirements, versions,
                    mock(ProjectSolutionRepository.class), mock(ProjectConflictRepository.class), fingerprints,
                    codec, mock(ReformulationPositiveReviewGuard.class)));
            persistence = proxy(new PortfolioAnalysisPersistenceService(projectRepository, requirements, versions, jobs, items,
                    snapshots, repositories.getRepository(RequirementElementMappingRepository.class),
                    repositories.getRepository(RequirementRelationMappingRepository.class), mock(AnalysisHypothesisLinkPort.class),
                    codec, new PortfolioSnapshotSelectionReader(shared, codec), new PortfolioSnapshotEvidenceReader(shared, codec),
                    new DefaultListableBeanFactory().getBeanProvider(ProjectProviderBinding.class)));
            selection = proxy(new CopilotResultPersistenceService(requirements, snapshots, items));
            if (realEnrichment) {
                var products = mock(ProductCatalogService.class);
                var solutions = proxy(new SolutionPortfolioService(mock(SolutionDefinitionRepository.class), coverage,
                        mock(ProjectSolutionRepository.class), mock(RequirementSolutionLinkRepository.class),
                        repositories.getRepository(RequirementElementMappingRepository.class), snapshots,
                        mock(SolutionProductCandidateRepository.class), mock(com.taxonomy.catalog.api.TaxonomyNodeLookup.class),
                        projects, products, codec));
                completion = new CopilotCompletionService(solutions, products, 25, .25);
            } else {
                completion = mock(CopilotCompletionService.class);
            }
            var analysis = new ProjectRequirementAnalysisService(projects, persistence, null, null, null, null,
                    null, null, null, fingerprints, null, null, 100, 100, 900);
            var policy = mock(AiAutomationPolicy.class);
            when(policy.costPolicy()).thenReturn(AiCostPolicy.METERED);
            copilot = new CopilotAutomationService(analysis, projects, fingerprints, policy, selector,
                    selection, completion, null, mock(ExecutorService.class));
            seed(); resetReads();
        }

        void seed() {
            transaction(em -> {
                String tenant = PortfolioScope.key("owner", scope);
                var project = new ArchitectureProject(tenant, scope.workspaceId(), "owner", "READ", "Snapshot reads", "",
                        ProjectStatus.PLANNING, TIME);
                em.persist(project); em.flush(); projectId = project.getId();
                var other = new ArchitectureProject(tenant, scope.workspaceId(), "owner", "OTHER", "Other project", "",
                        ProjectStatus.PLANNING, TIME);
                em.persist(other); em.flush(); otherProjectId = other.getId();
                var requirement = new ProjectRequirement(project, "REQ-1", "Published information", RequirementStatus.APPROVED,
                        73, Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, "owner", TIME);
                em.persist(requirement); em.flush(); requirementId = requirement.getId();
                var version = new ProjectRequirementVersion(requirement, 1, "Original requirement text", "a".repeat(64),
                        "Initial source", "owner", TIME, null, null, null, null, null, null);
                em.persist(version); em.flush(); versionId = version.getId();
                requirement.pointToVersion(versionId, TIME);
                for (int pass = 0; pass < 2; pass++) {
                    var key = new CopilotOperationKey(false, OPERATION, pass + 1, 2, AnalysisAutomationProfile.FULL,
                            realEnrichment, realEnrichment);
                    var job = new RequirementAnalysisJob(jobIds.get(pass), project, key.value(), "CUSTOM_OPENAI", 50,
                            "owner", scope.workspaceId(), 1, TIME.plusSeconds(pass));
                    job.recordAnalysisReasonLength(AnalysisReasonLength.DETAILED);
                    em.persist(job); em.flush();
                    var item = new RequirementAnalysisJobItem(job, requirement, version);
                    em.persist(item); em.flush();
                    var node = new TaxonomyNodeDto(); node.setCode("BP-1000"); node.setDescriptionEn(LARGE_EVIDENCE);
                    var analysis = new AnalysisResult(Map.of("BP-1000", 50d + pass * 25d), List.of(node));
                    analysis.setReasons(Map.of("BP-1000", "Reason for pass " + pass));
                    analysis.setWarnings(List.of("CALL_BUDGET"));
                    var gaps = new GapAnalysisView(); gaps.setNotes(List.of(LARGE_EVIDENCE));
                    var patterns = new PatternDetectionView(); patterns.setNotes(List.of(LARGE_EVIDENCE));
                    var recommendation = new ArchitectureRecommendation(); recommendation.setReasoning(List.of(LARGE_EVIDENCE));
                    var snapshot = new RequirementAnalysisSnapshot(snapshotIds.get(pass), project, requirement, version, job,
                            AnalysisStatus.PARTIAL, "snapshot-reference-" + pass, "CUSTOM_OPENAI", "model", "p".repeat(64), "t".repeat(64),
                            scope.workspaceId(), scope.currentBranch(), "c".repeat(40), "owner", TIME.plusSeconds(pass), 123L + pass, 1,
                            "CALL_BUDGET", codec.write(analysis), codec.write(gaps), codec.write(patterns), codec.write(recommendation));
                    em.persist(snapshot); em.flush();
                    var element = new RequirementElementMapping(snapshot, "BP-1000", "Decision", "BP", 70, .7, .8,
                            MappingOrigin.DIRECT, "BP/BP-1000", "Requirement applies to this capability", true);
                    element.review(ReviewStatus.CONFIRMED, ActionStatus.REUSE, "Existing capability verified", "reviewer",
                            "Approved mapping", TIME);
                    em.persist(element);
                    var relation = new RequirementRelationMapping(snapshot, "BP-1000", "CP-1041", "USES", "LLM_SUPPORTED",
                            "STRUCTURAL", .7, .8, "Capability uses the service");
                    relation.review(ReviewStatus.CONFIRMED, "reviewer", "Approved relation", TIME);
                    em.persist(relation);
                    item.complete(AnalysisStatus.PARTIAL, snapshot.getId(), TIME.plusSeconds(pass + 2), "CALL_BUDGET");
                    job.complete(0, 1, 0, "CALL_BUDGET", TIME.plusSeconds(pass + 2));
                }
                requirement.pointToAnalysis(snapshotIds.get(1), TIME.plusSeconds(3));
            });
        }

        List<String> payloadSelects() {
            return sql.stream().filter(statement -> statement.contains("analysis_payload")).toList();
        }
        void assertNoResultEntityLoads() {
            for (Class<?> type : List.of(RequirementAnalysisSnapshot.class, RequirementElementMapping.class,
                    RequirementRelationMapping.class))
                assertThat(factory.getStatistics().getEntityStatistics(type.getName()).getLoadCount())
                        .as("Read DTOs must not hydrate %s", type.getSimpleName()).isZero();
        }
        void resetReads() { sql.clear(); factory.getStatistics().clear(); }
        void transaction(Consumer<EntityManager> work) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> work.accept(shared));
        }
        @SuppressWarnings("unchecked") <T> T proxy(T target) {
            var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            return (T) proxy.getProxy();
        }
        @Override public void close() { factory.close(); }
    }
}
