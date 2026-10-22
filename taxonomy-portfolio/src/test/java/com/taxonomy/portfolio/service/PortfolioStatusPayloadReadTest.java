package com.taxonomy.portfolio.service;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Status polling must not materialize immutable result payloads through lazy composite-key associations. */
class PortfolioStatusPayloadReadTest {
    @ParameterizedTest
    @ValueSource(strings = {"requirement", "job-item"})
    void entityMetadataLoadsDoNotReadSelectedSnapshotClobs(String entity) {
        try (var f = new Fixture()) {
            f.transaction(em -> {
                if (entity.equals("requirement")) {
                    var requirement = em.find(ProjectRequirement.class, f.requirementId);
                    assertThat(requirement.getCurrentAnalysisSnapshotId()).isEqualTo(f.oldSnapshotId);
                    assertThat(requirement.getCurrentAnalysisSnapshot().getId()).isEqualTo(f.oldSnapshotId);
                    assertThat(requirement.getCurrentAnalysisSnapshot().getRequirementId()).isEqualTo(f.requirementId);
                    assertThat(requirement.getCurrentAnalysisSnapshot().getProjectId()).isEqualTo(f.projectId);
                    assertThat(requirement.getCurrentAnalysisSnapshot().getScopeKey()).isEqualTo(PortfolioScope.key("owner", f.scope));
                } else {
                    var item = em.createQuery("select i from RequirementAnalysisJobItem i where i.jobId = :job",
                                    RequirementAnalysisJobItem.class).setParameter("job", f.jobId).getSingleResult();
                    assertThat(item.getSnapshotId()).isEqualTo(f.oldSnapshotId);
                    assertThat(item.getSnapshot().getId()).isEqualTo(f.oldSnapshotId);
                    assertThat(item.getSnapshot().getJobId()).isEqualTo(f.jobId);
                    assertThat(item.getSnapshot().getRequirementId()).isEqualTo(f.requirementId);
                    assertThat(item.getSnapshot().getRequirementVersionId()).isEqualTo(f.oldVersionId);
                    assertThat(item.getSnapshot().getProjectId()).isEqualTo(f.projectId);
                    assertThat(item.getSnapshot().getScopeKey()).isEqualTo(PortfolioScope.key("owner", f.scope));
                    assertThat(item.getRequirementVersionId()).isEqualTo(f.oldVersionId);
                }
            });
            f.assertNoSnapshotPayloadRead();
        }
    }

    @Test
    void snapshotIdentityViewsPreserveExactPhysicalCompositeForeignKeysAndUniqueKeys() {
        try (var f = new Fixture()) {
            f.transaction(em -> em.unwrap(org.hibernate.Session.class).doWork(connection -> {
                var metadata = connection.getMetaData();
                var requirement = new java.util.LinkedHashMap<String, String>();
                requirement.put("CURRENT_SNAPSHOT_ID", "ID");
                requirement.put("ID", "REQUIREMENT_ID");
                requirement.put("PROJECT_ID", "PROJECT_ID");
                requirement.put("SCOPE_KEY", "SCOPE_KEY");
                var item = new java.util.LinkedHashMap<String, String>();
                item.put("SNAPSHOT_ID", "ID");
                item.put("JOB_ID", "JOB_ID");
                item.put("REQUIREMENT_ID", "REQUIREMENT_ID");
                item.put("REQUIREMENT_VERSION_ID", "REQUIREMENT_VERSION_ID");
                item.put("PROJECT_ID", "PROJECT_ID");
                item.put("SCOPE_KEY", "SCOPE_KEY");
                for (var expected : java.util.Map.of("PROJECT_REQUIREMENT", requirement,
                        "REQ_ANALYSIS_ITEM", item).entrySet()) {
                    var keys = new java.util.HashMap<String, java.util.Map<String, String>>();
                    try (var rows = metadata.getImportedKeys(null, "PUBLIC", expected.getKey())) {
                        while (rows.next()) {
                            if (rows.getString("PKTABLE_NAME").equals("REQ_ANALYSIS_SNAPSHOT"))
                                keys.computeIfAbsent(rows.getString("FK_NAME"), key -> new java.util.HashMap<>())
                                        .put(rows.getString("FKCOLUMN_NAME"), rows.getString("PKCOLUMN_NAME"));
                        }
                    }
                    assertThat(keys.values()).as(expected.getKey() + " snapshot FK").containsExactly(expected.getValue());
                }
                var uniqueKeys = new java.util.HashMap<String, java.util.Set<String>>();
                try (var rows = metadata.getIndexInfo(null, "PUBLIC", "REQ_ANALYSIS_SNAPSHOT", true, false)) {
                    while (rows.next()) {
                        if (rows.getString("COLUMN_NAME") != null)
                            uniqueKeys.computeIfAbsent(rows.getString("INDEX_NAME"), key -> new java.util.HashSet<>())
                                    .add(rows.getString("COLUMN_NAME"));
                    }
                }
                assertThat(uniqueKeys.values()).containsExactlyInAnyOrder(
                        java.util.Set.of("ID"),
                        java.util.Set.of("ID", "SCOPE_KEY"),
                        java.util.Set.of("ID", "REQUIREMENT_VERSION_ID", "REQUIREMENT_ID", "PROJECT_ID", "SCOPE_KEY"),
                        java.util.Set.of("ID", "REQUIREMENT_ID", "PROJECT_ID", "SCOPE_KEY"),
                        java.util.Set.of("ID", "JOB_ID", "REQUIREMENT_ID", "REQUIREMENT_VERSION_ID", "PROJECT_ID", "SCOPE_KEY"));
            }));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sibling-requirement", "other-project", "other-tenant", "wrong-version", "wrong-job"})
    void databaseRejectsSnapshotIdentityBindingsOutsideExactSource(String binding) {
        try (var f = new Fixture()) {
            if (binding.startsWith("wrong-")) {
                String otherJob = UUID.randomUUID().toString();
                if (binding.equals("wrong-job")) f.transaction(em -> em.persist(new RequirementAnalysisJob(otherJob,
                        em.find(ArchitectureProject.class, f.projectId), "other-job", "CUSTOM_OPENAI", 50, "owner",
                        f.scope.workspaceId(), 1, Fixture.TIME)));
                assertThatThrownBy(() -> f.transaction(em -> em.createNativeQuery(binding.equals("wrong-version")
                                ? "update req_analysis_item set requirement_version_id = :replacement where job_id = :job"
                                : "update req_analysis_item set job_id = :replacement where job_id = :job")
                        .setParameter("replacement", binding.equals("wrong-version") ? f.currentVersionId : otherJob)
                        .setParameter("job", f.jobId).executeUpdate()))
                        .isInstanceOfSatisfying(org.hibernate.exception.ConstraintViolationException.class,
                                failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
            } else {
                Long[] target = new Long[1];
                f.transaction(em -> {
                    Long projectId = binding.equals("other-project") ? f.otherProjectId : f.projectId;
                    if (binding.equals("other-tenant")) {
                        var foreignScope = new WorkspaceContext("owner", "other-workspace", "draft", "repository");
                        var project = new ArchitectureProject(PortfolioScope.key("owner", foreignScope), "other-workspace", "owner", "FOREIGN",
                                "Foreign project", "", ProjectStatus.PLANNING, Fixture.TIME);
                        em.persist(project); em.flush(); projectId = project.getId();
                    }
                    f.addRequirement(em, projectId, "REQ-FOREIGN", RequirementStatus.APPROVED);
                    target[0] = em.createQuery("select r.id from ProjectRequirement r where r.requirementKey = 'REQ-FOREIGN'",
                            Long.class).getSingleResult();
                });
                assertThatThrownBy(() -> f.transaction(em -> em.createNativeQuery(
                                "update project_requirement set current_snapshot_id = :snapshot where id = :requirement")
                        .setParameter("snapshot", f.oldSnapshotId).setParameter("requirement", target[0]).executeUpdate()))
                        .isInstanceOfSatisfying(org.hibernate.exception.ConstraintViolationException.class,
                                failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
            }
            f.resetReads();
            f.transaction(em -> {
                assertThat(em.find(ProjectRequirement.class, f.requirementId).getCurrentAnalysisSnapshotId())
                        .isEqualTo(f.oldSnapshotId);
                var item = em.createQuery("from RequirementAnalysisJobItem where jobId = :job", RequirementAnalysisJobItem.class)
                        .setParameter("job", f.jobId).getSingleResult();
                assertThat(item.getRequirementVersionId()).isEqualTo(f.oldVersionId);
                assertThat(item.getSnapshotId()).isEqualTo(f.oldSnapshotId);
            });
            f.assertNoSnapshotPayloadRead();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"job", "jobs"})
    void jobStatusRetainsExactItemVersionWithoutReadingSnapshotPayloads(String read) {
        try (var f = new Fixture()) {
            var jobs = read.equals("job") ? List.of(f.persistence.getJob(f.jobId, f.projectId, "owner", f.scope))
                    : f.persistence.listJobs(f.projectId, "owner", f.scope);
            assertThat(jobs).hasSize(1);
            var job = jobs.getFirst();
            assertThat(job.id()).isEqualTo(f.jobId);
            assertThat(job.status()).isEqualTo(AnalysisStatus.RUNNING);
            assertThat(job.provider()).isEqualTo("CUSTOM_OPENAI");
            assertThat(job.analysisReasonLength()).isEqualTo(com.taxonomy.dto.AnalysisReasonLength.DETAILED);
            assertThat(job.items()).singleElement().satisfies(item -> {
                assertThat(item.requirementKey()).isEqualTo("REQ-1");
                assertThat(item.requirementId()).isEqualTo(f.requirementId);
                assertThat(item.requirementVersionId()).isEqualTo(f.oldVersionId);
                assertThat(item.requirementVersionNumber()).isEqualTo(1);
                assertThat(item.status()).isEqualTo(AnalysisStatus.RUNNING);
                assertThat(item.snapshotId()).isEqualTo(f.oldSnapshotId);
                assertThat(item.attempt()).isEqualTo(2);
            });
            f.assertNoSnapshotPayloadRead();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"requirement", "requirements", "approved"})
    void requirementReadsRetainSourceAndCurrentVersionWithoutHydratingSelectedSnapshot(String read) {
        try (var f = new Fixture()) {
            var rows = switch (read) {
                case "requirement" -> List.of(f.projects.getRequirement(f.projectId, f.requirementId, "owner", f.scope));
                case "requirements" -> f.projects.listRequirements(f.projectId, "owner", f.scope);
                default -> f.projects.listApprovedRequirements(f.projectId, "owner", f.scope, 0, 1).requirements();
            };
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.id()).isEqualTo(f.requirementId);
                assertThat(row.projectId()).isEqualTo(f.projectId);
                assertThat(row.requirementKey()).isEqualTo("REQ-1");
                assertThat(row.title()).isEqualTo("Published information");
                assertThat(row.status()).isEqualTo(RequirementStatus.APPROVED);
                assertThat(row.priority()).isEqualTo(73);
                assertThat(row.criticality()).isEqualTo(Criticality.HIGH);
                assertThat(row.requirementType()).isEqualTo(RequirementType.FUNCTIONAL);
                assertThat(row.reviewStatus()).isEqualTo(ReviewStatus.PROPOSED);
                assertThat(row.ownerUsername()).isEqualTo("owner");
                assertThat(row.currentVersionId()).isEqualTo(f.currentVersionId);
                assertThat(row.currentAnalysisSnapshotId()).isEqualTo(f.oldSnapshotId);
                assertThat(row.createdAt()).isEqualTo(Fixture.TIME);
                assertThat(row.updatedAt()).isEqualTo(Fixture.TIME.plusSeconds(2));
                var version = row.currentVersion();
                assertThat(version.id()).isEqualTo(f.currentVersionId);
                assertThat(version.versionNumber()).isEqualTo(2);
                assertThat(version.text()).isEqualTo("Updated requirement text");
                assertThat(version.contentHash()).isEqualTo("b".repeat(64));
                assertThat(version.changeReason()).isEqualTo("Revised scope");
                assertThat(version.createdBy()).isEqualTo("editor");
                assertThat(version.source()).isEqualTo(new SourceReference(12L, 34L, List.of(5L, 8L),
                        "Section 2", 7, "Original published wording"));
            });
            f.assertNoSnapshotPayloadRead();
        }
    }

    @Test
    void requirementWithoutCurrentVersionPointerKeepsExistingLatestVersionFallback() {
        try (var f = new Fixture()) {
            f.transaction(em -> em.find(ProjectRequirement.class, f.requirementId).pointToVersion(null, Fixture.TIME));
            f.resetReads();
            var row = f.projects.getRequirement(f.projectId, f.requirementId, "owner", f.scope);
            assertThat(row.currentVersionId()).isNull();
            assertThat(row.currentVersion().id()).isEqualTo(f.currentVersionId);
            assertThat(row.currentVersion().versionNumber()).isEqualTo(2);
            f.assertNoSnapshotPayloadRead();
        }
    }

    @Test
    void explicitlySelectedOlderVersionIsNotReplacedByLatestVersion() {
        try (var f = new Fixture()) {
            f.transaction(em -> em.find(ProjectRequirement.class, f.requirementId)
                    .pointToVersion(f.oldVersionId, Fixture.TIME));
            f.resetReads();
            var row = f.projects.getRequirement(f.projectId, f.requirementId, "owner", f.scope);
            assertThat(row.currentVersionId()).isEqualTo(f.oldVersionId);
            assertThat(row.currentVersion().id()).isEqualTo(f.oldVersionId);
            assertThat(row.currentVersion().versionNumber()).isEqualTo(1);
            assertThat(row.currentVersion().text()).isEqualTo("Original requirement text");
            assertThat(row.currentVersion().source())
                    .isEqualTo(new SourceReference(null, null, List.of(), null, null, null));
            f.assertNoSnapshotPayloadRead();
        }
    }

    @Test
    void approvedPagesKeepExactFilterOrderAndHasNextWithMultipleVersions() {
        try (var f = new Fixture()) {
            f.transaction(em -> {
                f.addRequirement(em, f.projectId, "REQ-0", RequirementStatus.DRAFT);
                f.addRequirement(em, f.projectId, "REQ-3", RequirementStatus.APPROVED);
                f.addRequirement(em, f.projectId, "REQ-2", RequirementStatus.APPROVED);
                f.addRequirement(em, f.otherProjectId, "REQ-X", RequirementStatus.APPROVED);
            });
            f.resetReads();
            var first = f.projects.listApprovedRequirements(f.projectId, "owner", f.scope, 0, 2);
            assertThat(first.requirements()).extracting(RequirementView::requirementKey)
                    .containsExactly("REQ-1", "REQ-2");
            assertThat(first.requirements().getFirst().currentVersion().versionNumber()).isEqualTo(2);
            assertThat(first.hasNext()).isTrue();
            var second = f.projects.listApprovedRequirements(f.projectId, "owner", f.scope, 1, 2);
            assertThat(second.requirements()).extracting(RequirementView::requirementKey).containsExactly("REQ-3");
            assertThat(second.hasNext()).isFalse();
            var empty = f.projects.listApprovedRequirements(f.projectId, "owner", f.scope, 2, 2);
            assertThat(empty.requirements()).isEmpty();
            assertThat(empty.hasNext()).isFalse();
            f.assertNoSnapshotPayloadRead();
        }
    }

    @Test
    void activeCopilotStatusOnlyReadsMetadataForExistingPassAndSelectedSnapshot() {
        try (var f = new Fixture()) {
            var operation = f.copilot.getOperation(f.projectId, Fixture.OPERATION, "owner", f.scope);
            assertThat(operation.status()).isEqualTo(AnalysisStatus.RUNNING);
            assertThat(operation.verificationPasses()).isEqualTo(1);
            assertThat(operation.selectedSnapshotId()).isEqualTo(f.oldSnapshotId);
            assertThat(operation.jobs()).singleElement().satisfies(job -> assertThat(job.id()).isEqualTo(f.jobId));
            f.assertNoSnapshotPayloadRead();
        }
    }

    @Test
    void snapshotHistoryOnlyReadsSummaryFields() {
        try (var f = new Fixture()) {
            var rows = f.persistence.listRequirementSnapshots(f.projectId, f.requirementId, "owner", f.scope);
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.id()).isEqualTo(f.oldSnapshotId);
                assertThat(row.requirementId()).isEqualTo(f.requirementId);
                assertThat(row.requirementKey()).isEqualTo("REQ-1");
                assertThat(row.requirementVersionId()).isEqualTo(f.oldVersionId);
                assertThat(row.requirementVersionNumber()).isEqualTo(1);
                assertThat(row.status()).isEqualTo(AnalysisStatus.PARTIAL);
                assertThat(row.warningCount()).isEqualTo(3);
                assertThat(row.errorMessage()).isEqualTo("CALL_BUDGET");
            });
            f.assertNoSnapshotPayloadRead();
        }
    }

    @Test
    void metadataReadsDoNotCrossWorkspaceOrProjectBoundaries() {
        try (var f = new Fixture()) {
            var other = new WorkspaceContext("owner", "other-workspace", "draft", "repository");
            assertThatThrownBy(() -> f.persistence.getJob(f.jobId, f.projectId, "owner", other))
                    .isInstanceOfSatisfying(PortfolioException.class, e -> assertThat(e.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
            assertThatThrownBy(() -> f.persistence.listJobs(f.projectId, "owner", other))
                    .isInstanceOfSatisfying(PortfolioException.class, e -> assertThat(e.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
            assertThatThrownBy(() -> f.persistence.getJob(f.jobId, f.otherProjectId, "owner", f.scope))
                    .isInstanceOfSatisfying(PortfolioException.class, e -> assertThat(e.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
            assertThat(f.persistence.listJobs(f.otherProjectId, "owner", f.scope)).isEmpty();
            assertThatThrownBy(() -> f.projects.getRequirement(f.otherProjectId, f.requirementId, "owner", f.scope))
                    .isInstanceOfSatisfying(PortfolioException.class, e -> assertThat(e.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
            assertThatThrownBy(() -> f.persistence.listRequirementSnapshots(f.otherProjectId, f.requirementId, "owner", f.scope))
                    .isInstanceOfSatisfying(PortfolioException.class, e -> assertThat(e.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
            f.assertNoSnapshotPayloadRead();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"job", "jobs"})
    void emptyJobRetainsItsStoredHeaderAndProviderBinding(String read) {
        try (var f = new Fixture()) {
            String emptyId = UUID.randomUUID().toString();
            var binding = new com.taxonomy.extension.api.plugin.PluginInvocation(
                    new com.taxonomy.extension.api.plugin.PluginIdentity("status.provider", "1.2.3", "c".repeat(64)),
                    "d".repeat(64));
            f.transaction(em -> {
                var job = new RequirementAnalysisJob(emptyId, em.find(ArchitectureProject.class, f.otherProjectId),
                        "auto:external-correlation", "EXTENSION", 43, "owner", f.scope.workspaceId(), 0, Fixture.TIME);
                job.bindProvider("EXTENSION", binding);
                job.markRunning(Fixture.TIME.plusSeconds(4));
                job.complete(0, 0, 0, "Empty job completed", Fixture.TIME.plusSeconds(5));
                em.persist(job);
            });
            f.resetReads();
            var jobs = read.equals("job") ? List.of(f.persistence.getJob(emptyId, f.otherProjectId, "owner", f.scope))
                    : f.persistence.listJobs(f.otherProjectId, "owner", f.scope);
            assertThat(jobs).singleElement().satisfies(job -> {
                assertThat(job.id()).isEqualTo(emptyId);
                assertThat(job.projectId()).isEqualTo(f.otherProjectId);
                assertThat(job.items()).isEmpty();
                assertThat(job.status()).isEqualTo(AnalysisStatus.SUCCESS);
                assertThat(job.idempotencyKey()).isEqualTo("auto:external-correlation");
                assertThat(job.provider()).isEqualTo("EXTENSION");
                assertThat(job.providerBinding()).isEqualTo(binding);
                assertThat(job.analysisReasonLength()).isNull();
                assertThat(job.maxArchitectureNodes()).isEqualTo(43);
                assertThat(job.requestedBy()).isEqualTo("owner");
                assertThat(job.workspaceId()).isEqualTo(f.scope.workspaceId());
                assertThat(job.createdAt()).isEqualTo(Fixture.TIME);
                assertThat(job.startedAt()).isEqualTo(Fixture.TIME.plusSeconds(4));
                assertThat(job.completedAt()).isEqualTo(Fixture.TIME.plusSeconds(5));
                assertThat(job.totalItems()).isZero();
                assertThat(job.successfulItems()).isZero();
                assertThat(job.partialItems()).isZero();
                assertThat(job.failedItems()).isZero();
                assertThat(job.errorSummary()).isEqualTo("Empty job completed");
            });
            f.assertNoSnapshotPayloadRead();
        }
    }

    @Test
    void listJobsReadsAllHeadersAndItemsInOneOrderedMetadataStatement() {
        try (var f = new Fixture()) {
            String emptyId = UUID.randomUUID().toString();
            f.transaction(em -> em.persist(new RequirementAnalysisJob(emptyId,
                    em.find(ArchitectureProject.class, f.projectId), null, "MOCK", 25,
                    "owner", f.scope.workspaceId(), 0, Fixture.TIME.plusSeconds(10))));
            f.resetReads();
            var jobs = f.persistence.listJobs(f.projectId, "owner", f.scope);
            assertThat(jobs).extracting(AnalysisJobView::id).containsExactly(emptyId, f.jobId);
            assertThat(jobs.getFirst().items()).isEmpty();
            assertThat(jobs.getFirst().idempotencyKey()).isNull();
            assertThat(jobs.getLast().items()).singleElement()
                    .satisfies(item -> assertThat(item.snapshotId()).isEqualTo(f.oldSnapshotId));
            assertThat(f.sql).filteredOn(statement -> {
                String sql = statement.toLowerCase(java.util.Locale.ROOT);
                return sql.startsWith("select") && (sql.contains("req_analysis_job") || sql.contains("req_analysis_item"));
            }).as("All job headers and their items must share one statement snapshot").hasSize(1);
            f.assertNoSnapshotPayloadRead();
        }
    }

    @Test
    void createAndReuseResponsesIncludeTheNewJobAndItsAdmittedItems() {
        try (var f = new Fixture()) {
            var created = f.persistence.createOrReuseJob(f.projectId, List.of(f.requirementId), "CUSTOM_OPENAI",
                    25, "new-job-key", "owner", f.scope);
            assertThat(created.status()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(created.idempotencyKey()).isEqualTo("new-job-key");
            assertThat(created.providerBinding()).isNull();
            assertThat(created.analysisReasonLength()).isEqualTo(com.taxonomy.dto.AnalysisReasonLength.STANDARD);
            assertThat(created.totalItems()).isEqualTo(1);
            assertThat(created.items()).singleElement().satisfies(item -> {
                assertThat(item.requirementVersionId()).isEqualTo(f.currentVersionId);
                assertThat(item.requirementVersionNumber()).isEqualTo(2);
                assertThat(item.attempt()).isEqualTo(1);
                assertThat(item.status()).isEqualTo(AnalysisStatus.PENDING);
                assertThat(item.snapshotId()).isNull();
            });
            assertThat(f.persistence.createOrReuseJob(f.projectId, List.of(f.requirementId), "CUSTOM_OPENAI",
                    25, "new-job-key", "owner", f.scope)).usingRecursiveComparison()
                    .ignoringFields("createdAt", "startedAt", "completedAt").isEqualTo(created);
        }
    }

    @Test
    void completionResponseIncludesTheUncommittedAggregateUpdate() {
        try (var f = new Fixture()) {
            f.transaction(em -> em.createQuery("select i from RequirementAnalysisJobItem i where i.jobId = :id",
                            RequirementAnalysisJobItem.class).setParameter("id", f.jobId).getSingleResult()
                    .complete(AnalysisStatus.PARTIAL, f.oldSnapshotId, Fixture.TIME, "CALL_BUDGET"));
            var completed = f.persistence.completeJob(f.jobId, f.projectId, PortfolioScope.key("owner", f.scope));
            assertThat(completed.status()).isEqualTo(AnalysisStatus.PARTIAL);
            assertThat(completed.successfulItems()).isZero();
            assertThat(completed.partialItems()).isEqualTo(1);
            assertThat(completed.failedItems()).isZero();
            assertThat(completed.completedAt()).isNotNull();
            assertThat(completed.errorSummary()).isEqualTo("REQ-1: CALL_BUDGET");
            assertThat(completed.items()).singleElement()
                    .satisfies(item -> assertThat(item.status()).isEqualTo(AnalysisStatus.PARTIAL));
            assertThat(f.persistence.getJob(f.jobId, f.projectId, "owner", f.scope)).usingRecursiveComparison()
                    .ignoringFields("createdAt", "startedAt", "completedAt").isEqualTo(completed);
        }
    }

    @Test
    void retryResponseIncludesTheNewAttemptAndVersionWithoutNormalizingStoredCounts() {
        try (var f = new Fixture()) {
            f.transaction(em -> {
                var item = em.createQuery("select i from RequirementAnalysisJobItem i where i.jobId = :id",
                        RequirementAnalysisJobItem.class).setParameter("id", f.jobId).getSingleResult();
                item.fail("Failed provider call", Fixture.TIME);
                item.getJob().complete(0, 0, 1, "Failed provider call", Fixture.TIME);
            });
            var retried = f.persistence.prepareFailedItemsForRetry(f.jobId, f.projectId, "owner", f.scope);
            assertThat(retried.status()).isEqualTo(AnalysisStatus.RUNNING);
            assertThat(retried.failedItems()).isEqualTo(1);
            assertThat(retried.completedAt()).isNull();
            assertThat(retried.errorSummary()).isNull();
            assertThat(retried.items()).singleElement().satisfies(item -> {
                assertThat(item.status()).isEqualTo(AnalysisStatus.PENDING);
                assertThat(item.attempt()).isEqualTo(3);
                assertThat(item.requirementVersionId()).isEqualTo(f.currentVersionId);
                assertThat(item.requirementVersionNumber()).isEqualTo(2);
                assertThat(item.snapshotId()).isNull();
                assertThat(item.errorMessage()).isNull();
            });
            assertThat(f.persistence.getJob(f.jobId, f.projectId, "owner", f.scope)).usingRecursiveComparison()
                    .ignoringFields("createdAt", "startedAt", "completedAt").isEqualTo(retried);
        }
    }

    @Test
    void cancellationKeepsTheStoredTerminalStatusAndCompletedItemEvidence() {
        try (var f = new Fixture()) {
            f.transaction(em -> em.createQuery("select i from RequirementAnalysisJobItem i where i.jobId = :id",
                            RequirementAnalysisJobItem.class).setParameter("id", f.jobId).getSingleResult()
                    .complete(AnalysisStatus.PARTIAL, f.oldSnapshotId, Fixture.TIME, "CALL_BUDGET"));
            var jobs = new JpaRepositoryFactory(f.shared).getRepository(RequirementAnalysisJobRepository.class);
            var control = f.proxy(new CopilotJobControlService(jobs, f.persistence, f.shared));
            var cancelled = control.cancel(f.jobId, f.projectId, "owner", f.scope);
            assertThat(cancelled.status()).isEqualTo(AnalysisStatus.CANCELLED);
            assertThat(cancelled.items()).singleElement().satisfies(item -> {
                assertThat(item.status()).isEqualTo(AnalysisStatus.PARTIAL);
                assertThat(item.snapshotId()).isEqualTo(f.oldSnapshotId);
                assertThat(item.requirementVersionId()).isEqualTo(f.oldVersionId);
            });
            f.resetReads();
            assertThat(f.persistence.getJob(f.jobId, f.projectId, "owner", f.scope)).isEqualTo(cancelled);
            f.assertNoSnapshotPayloadRead();
        }
    }

    static final class Fixture implements AutoCloseable {
        static final Instant TIME = Instant.parse("2026-10-17T05:00:00Z");
        static final String OPERATION = "a".repeat(64);
        final WorkspaceContext scope = new WorkspaceContext("owner", "owned-workspace", "draft", "repository");
        final List<String> sql = new ArrayList<>();
        Consumer<String> sqlHook = statement -> { };
        final SessionFactory factory;
        final JpaTransactionManager transactions;
        final EntityManager shared;
        final ProjectPortfolioService projects;
        final PortfolioAnalysisPersistenceService persistence;
        final CopilotAutomationService copilot;
        final String jobId = UUID.randomUUID().toString();
        final String oldSnapshotId = UUID.randomUUID().toString();
        Long projectId, otherProjectId, requirementId, oldVersionId, currentVersionId;

        Fixture() {
            Configuration configuration = new Configuration();
            for (Class<?> entity : List.of(ArchitectureProject.class, ProjectRequirement.class,
                    ProjectRequirementVersion.class, RequirementAnalysisJob.class, RequirementAnalysisJobItem.class,
                    RequirementAnalysisSnapshot.class, SnapshotReference.class,
                    RequirementElementMapping.class, RequirementRelationMapping.class))
                configuration.addAnnotatedClass(entity);
            configuration.getProperties().put("hibernate.session_factory.statement_inspector", (StatementInspector) statement -> {
                sqlHook.accept(statement); sql.add(statement); return statement;
            });
            factory = configuration.setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                    .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:status-" + UUID.randomUUID() + ";shutdown=true")
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
            var codec = new PortfolioJsonCodec(JsonMapper.builder().build());
            var fingerprints = mock(PortfolioFingerprintService.class);
            projects = proxy(new ProjectPortfolioService(projectRepository, requirements, versions,
                    mock(ProjectSolutionRepository.class), mock(ProjectConflictRepository.class), fingerprints,
                    codec, mock(ReformulationPositiveReviewGuard.class)));
            var beans = new DefaultListableBeanFactory();
            persistence = proxy(new PortfolioAnalysisPersistenceService(projectRepository, requirements, versions, jobs, items,
                    snapshots, repositories.getRepository(RequirementElementMappingRepository.class),
                    repositories.getRepository(RequirementRelationMappingRepository.class), mock(AnalysisHypothesisLinkPort.class),
                    codec, new PortfolioSnapshotSelectionReader(shared, codec), new PortfolioSnapshotEvidenceReader(shared, codec),
                    beans.getBeanProvider(ProjectProviderBinding.class)));
            var analysis = new ProjectRequirementAnalysisService(projects, persistence, null, null, null, null,
                    null, null, null, fingerprints, null, null, 100, 100, 900);
            var policy = mock(AiAutomationPolicy.class);
            when(policy.costPolicy()).thenReturn(AiCostPolicy.METERED);
            copilot = new CopilotAutomationService(analysis, projects, fingerprints, policy, new CopilotResultSelector(),
                    null, null, null, mock(ExecutorService.class));
            seed(); resetReads();
        }

        void seed() {
            transaction(em -> {
                String tenant = PortfolioScope.key("owner", scope);
                var project = new ArchitectureProject(tenant, scope.workspaceId(), "owner", "STATUS", "Status reference", "",
                        ProjectStatus.PLANNING, TIME);
                em.persist(project); em.flush(); projectId = project.getId();
                var other = new ArchitectureProject(tenant, scope.workspaceId(), "owner", "OTHER", "Other project", "",
                        ProjectStatus.PLANNING, TIME);
                em.persist(other); em.flush(); otherProjectId = other.getId();
                var requirement = new ProjectRequirement(project, "REQ-1", "Published information", RequirementStatus.APPROVED,
                        73, Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, "owner", TIME);
                em.persist(requirement); em.flush(); requirementId = requirement.getId();
                var oldVersion = new ProjectRequirementVersion(requirement, 1, "Original requirement text", "a".repeat(64),
                        "Initial scope", "owner", TIME, null, null, null, null, null, null);
                em.persist(oldVersion); em.flush(); oldVersionId = oldVersion.getId();
                var current = new ProjectRequirementVersion(requirement, 2, "Updated requirement text", "b".repeat(64),
                        "Revised scope", "editor", TIME.plusSeconds(1), 12L, 34L, "[5,8]", "Section 2", 7, "Original published wording");
                em.persist(current); em.flush(); currentVersionId = current.getId();
                requirement.pointToVersion(currentVersionId, TIME.plusSeconds(2));
                var key = new CopilotOperationKey(false, OPERATION, 1, 1, AnalysisAutomationProfile.STANDARD, false, false);
                var job = new RequirementAnalysisJob(jobId, project, key.value(), "CUSTOM_OPENAI", 50, "owner", scope.workspaceId(), 1, TIME);
                job.recordAnalysisReasonLength(com.taxonomy.dto.AnalysisReasonLength.DETAILED);
                em.persist(job); em.flush();
                var item = new RequirementAnalysisJobItem(job, requirement, oldVersion);
                em.persist(item); em.flush();
                String large = "{\"evidence\":\"" + "x".repeat(1_000_000) + "\"}";
                var snapshot = new RequirementAnalysisSnapshot(oldSnapshotId, project, requirement, oldVersion, job,
                        AnalysisStatus.PARTIAL, "status-reference", "CUSTOM_OPENAI", "model", "p".repeat(64), "t".repeat(64),
                        scope.workspaceId(), scope.currentBranch(), "c".repeat(40), "owner", TIME, 123L, 3, "CALL_BUDGET",
                        large, large, large, large);
                em.persist(snapshot); em.flush();
                requirement.pointToAnalysis(oldSnapshotId, TIME.plusSeconds(2));
                item.complete(AnalysisStatus.PARTIAL, oldSnapshotId, TIME, "CALL_BUDGET");
                item.prepareBudgetContinuation(); item.markRunning(TIME.plusSeconds(3)); job.markRunning(TIME.plusSeconds(3));
            });
        }

        void resetReads() { sql.clear(); factory.getStatistics().clear(); }

        void addRequirement(EntityManager em, Long ownerProjectId, String key, RequirementStatus status) {
            var project = em.find(ArchitectureProject.class, ownerProjectId);
            var requirement = new ProjectRequirement(project, key, key, status, 50, Criticality.MEDIUM,
                    RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, "owner", TIME);
            em.persist(requirement); em.flush();
            var version = new ProjectRequirementVersion(requirement, 1, key + " text", key + "-hash",
                    null, "owner", TIME, null, null, null, null, null, null);
            em.persist(version); em.flush();
            requirement.pointToVersion(version.getId(), TIME);
        }

        void assertNoSnapshotPayloadRead() {
            assertThat(sql).noneMatch(statement -> List.of("analysis_payload", "gap_payload", "pattern_payload", "recommendation_payload")
                    .stream().anyMatch(column -> statement.toLowerCase(java.util.Locale.ROOT).contains(column)));
            assertThat(factory.getStatistics().getEntityStatistics(RequirementAnalysisSnapshot.class.getName()).getLoadCount())
                    .as("Status and summary requests must not hydrate snapshot entities").isZero();
        }
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
    @Test
    void durableJobLanguageIsFrozenAndReturnedWithoutLoadingSnapshotPayloads() {
        try (var f = new Fixture()) {
            var created = f.persistence.createOrReuseJob(f.projectId, List.of(f.requirementId), "CUSTOM_OPENAI",
                    25, "language-job-key", "owner", f.scope, com.taxonomy.dto.AnalysisLanguage.GERMAN);
            assertThat(created.analysisLanguage()).isEqualTo(com.taxonomy.dto.AnalysisLanguage.GERMAN);
            var reused = f.persistence.createOrReuseJob(f.projectId, List.of(f.requirementId), "CUSTOM_OPENAI",
                    25, "language-job-key", "owner", f.scope, com.taxonomy.dto.AnalysisLanguage.ENGLISH);
            assertThat(reused.id()).isEqualTo(created.id());
            assertThat(reused.analysisLanguage()).isEqualTo(com.taxonomy.dto.AnalysisLanguage.GERMAN);
            f.assertNoSnapshotPayloadRead();
        }
    }

}
