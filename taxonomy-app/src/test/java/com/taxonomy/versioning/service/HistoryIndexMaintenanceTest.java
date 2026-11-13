package com.taxonomy.versioning.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.HistoryIndexGrant;
import com.taxonomy.workspace.service.DerivedProjection;
import com.taxonomy.workspace.service.WorkspaceFetchAuthority.Grant;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static com.taxonomy.versioning.service.HistoryIndexJournal.Kind.REBUILD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class HistoryIndexMaintenanceTest {
    @Test
    void recordedAuthorityIsRetriedWithoutWritingAnAdvancedOrDivergedBranch() throws Exception {
        try (var f = new Fixture()) {
            String recorded = f.git.commitDsl("draft","initial","owner","first");
            var operation = f.admit("advanced", recorded);
            f.git.commitDsl("draft","later","owner","next");
            var advanced = f.service.retry(operation.receipt().operationId());
            assertThat(advanced.phase()).isEqualTo("SUPERSEDED");
            assertThat(advanced.sourceCommit()).isEqualTo(recorded);
            verifyNoInteractions(f.indexRows);

            String unrelated = f.git.commitDsl("independent","other","owner","other root");
            var diverged = f.admit("unrelated", unrelated);
            assertThat(f.service.retry(diverged.receipt().operationId()).phase()).isEqualTo("MANUAL_REVIEW");
            verifyNoInteractions(f.indexRows);
        }
    }

    @Test
    void onlyTheRecordedFixtureScopeIsAdmittedAndAReplayKeepsItsTerminalResult() throws Exception {
        try (var f = new Fixture()) {
            String first = f.git.commitDsl("draft", "initial", "owner", "first");
            var done = f.service.submit(REBUILD, "draft", "browser-request", null);
            assertThat(done.phase()).isEqualTo("COMPLETED");
            assertThat(done.sourceCommit()).isEqualTo(first);
            assertThat(done.count()).isEqualTo(1);

            f.git.commitDsl("draft", "later", "owner", "next");
            assertThat(f.service.submit(REBUILD, "draft", "browser-request", null)).isEqualTo(done);
            verify(f.indexRows).save(any(com.taxonomy.versioning.model.ArchitectureCommitIndex.class));
            assertThat(f.service.get(done.operationId())).isEqualTo(done);
        }
    }

    @Test
    void aSettledIdempotentCommandCannotDiscloseItsReceiptAfterRepositoryAccessIsRevoked() throws Exception {
        try (var f = new Fixture()) {
            f.git.commitDsl("draft", "model", "owner", "first");
            var done = f.service.submit(REBUILD, "draft", "settled", null);
            when(f.members.canContribute(f.catalogueRow, "owner")).thenReturn(false);
            assertThatThrownBy(() -> f.service.retry(done.operationId())).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> f.service.submit(REBUILD, "draft", "settled", null)).isInstanceOf(AccessDeniedException.class);
            verify(f.indexRows).save(any());
        }
    }

    @Test
    void backgroundRecoveryRechecksTheInitiatingGrantAndSeparatesTheWorker() throws Exception {
        try (var f = new Fixture()) {
            String head = f.git.commitDsl("draft","initial","owner","first");
            var admitted = f.admit("revoked", head);
            doThrow(new AccessDeniedException("removed grant")).when(f.principals).checkHistoryIndex(f.grant, "owner", false, true);
            f.service.recover();
            var row = f.journal.get(f.scope, f.grant.principalId(), admitted.receipt().operationId()).receipt();
            assertThat(row.phase()).isEqualTo("DENIED");
            assertThat(row.actor()).isEqualTo("stable-owner");
            assertThat(row.executor()).isEqualTo("system:history-index");
            verifyNoInteractions(f.indexRows);
        }
    }

    @Test
    void authenticatedRetryRevokesTheWriteWhenTheRepositoryGrantChanges() throws Exception {
        try (var f = new Fixture()) {
            String head = f.git.commitDsl("draft","initial","owner","first");
            var admitted = f.admit("lost-membership", head);
            when(f.members.canContribute(f.catalogueRow, "owner")).thenReturn(false);
            assertThat(f.service.retry(admitted.receipt().operationId()).phase()).isEqualTo("DENIED");
            verifyNoInteractions(f.indexRows);
        }
    }

    @Test
    void aFreshLoginCanResumeAnExternalCommandAndArchiveTheOriginalReceipt() throws Exception {
        try (var f = new Fixture()) {
            String head = f.git.commitDsl("draft", "model", "owner", "one");
            var original = f.admit("external", head);
            doThrow(new com.taxonomy.workspace.service.WorkspaceFetchAuthority.FreshLoginRequired())
                    .when(f.principals).checkHistoryIndex(f.grant, "owner", false, true);
            f.service.recover();
            assertThat(f.service.get(original.receipt().operationId()).phase()).isEqualTo("AWAITING_AUTHORIZATION");
            assertThat(f.service.retry(original.receipt().operationId()).phase()).isEqualTo("COMPLETED");
            when(f.principals.authorizeHistoryIndex("owner", true)).thenReturn(f.grant);
            assertThat(f.service.archive(original.receipt().operationId()).archivedAt()).isNotNull();
        }
    }

    @Test
    void serviceRejectsInvalidAndMissingRefsBeforeAdmittingWork() throws Exception {
        try (var f = new Fixture()) {
            for (String branch : java.util.Arrays.asList(null, " ", "branch..name", "x".repeat(256))) {
                assertThatThrownBy(() -> f.service.submit(REBUILD, branch, null, null))
                        .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                                e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
            }
            assertThatThrownBy(() -> f.service.submit(REBUILD, "missing", "key", null))
                    .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
            assertThat(f.jdbc.queryForObject("select count(*) from history_index_operation", Integer.class)).isZero();
        }
    }

    @Test
    void retryRequiresTheOriginalBindingEvenAfterAnotherLoginReceivesTheSameScope() throws Exception {
        try (var f = new Fixture()) {
            var original = f.admit("same-scope", f.git.commitDsl("draft", "model", "owner", "one"));
            when(f.principals.authorizeHistoryIndex("owner", false)).thenReturn(new Grant(f.grant.principalId(), "OIDC", "new-binding"));
            assertThatThrownBy(() -> f.service.retry(original.receipt().operationId())).isInstanceOf(AccessDeniedException.class);
            assertThat(f.service.get(original.receipt().operationId()).attempts()).isZero();
            verifyNoInteractions(f.indexRows);
        }
    }

    @Test
    void retryableProjectionRemainsBoundToCapturedActualWorkspaceOwner() throws Exception {
        var selected = RepositoryContext.collaborativeWorkspace("repo", "pinned", "draft", "owner");
        try (var f = new Fixture(selected)) {
            var workspace = new com.taxonomy.workspace.model.UserWorkspace();
            workspace.setWorkspaceId("pinned");
            workspace.setUsername("other-owner");
            workspace.setSourceRepositoryId("repo");
            workspace.setCollaborative(true);
            workspace.setProvisioningStatus(com.taxonomy.workspace.model.WorkspaceProvisioningStatus.READY);
            var lock = mock(jakarta.persistence.Query.class);
            when(f.entities.createQuery(startsWith("update UserWorkspace"))).thenReturn(lock);
            when(lock.setParameter("id", "pinned")).thenReturn(lock);
            when(lock.executeUpdate()).thenReturn(1);
            @SuppressWarnings("unchecked")
            var lookup = (jakarta.persistence.TypedQuery<com.taxonomy.workspace.model.UserWorkspace>) mock(jakarta.persistence.TypedQuery.class);
            when(f.entities.createQuery(startsWith("select w from UserWorkspace"), eq(com.taxonomy.workspace.model.UserWorkspace.class))).thenReturn(lookup);
            when(lookup.setParameter("id", "pinned")).thenReturn(lookup);
            when(lookup.getSingleResult()).thenReturn(workspace);
            when(f.members.canUseWorkspace(f.catalogueRow, workspace, "owner")).thenReturn(true);
            f.git.commitDsl("draft", "model", "owner", "one");
            when(f.indexRows.save(any())).thenThrow(new org.springframework.dao.TransientDataAccessResourceException("temporary"));
            var pending = f.service.submit(REBUILD, "draft", "temporary", null);
            assertThat(pending.phase()).isEqualTo("RETRYABLE");
            workspace.setUsername("replacement-owner");
            assertThat(f.service.retry(pending.operationId()).phase()).isEqualTo("DENIED");
            verify(f.indexRows).save(any());
        }
    }

    @Test
    void grantRevokedInsideProjectionRollsBackDomainRowsAndRecordsDenial() throws Exception {
        try (var f = new Fixture()) {
            f.jdbc.execute("create table projection_probe (id integer primary key)");
            f.git.commitDsl("draft", "model", "owner", "one");
            when(f.indexRows.save(any())).thenAnswer(invocation -> {
                f.jdbc.update("insert into projection_probe values (1)");
                when(f.members.canContribute(f.catalogueRow, "owner")).thenReturn(false);
                return invocation.getArgument(0);
            });
            var decision = f.service.submit(REBUILD, "draft", "revoke-during-write", null);
            assertThat(decision.phase()).isEqualTo("DENIED");
            assertThat(f.jdbc.queryForObject("select count(*) from projection_probe", Integer.class)).isZero();
            assertThat(f.jdbc.queryForObject("select count(*) from history_index_decision where operation_id=? and decision_code='AUTHORIZATION_CHANGED'",
                    Integer.class, decision.operationId())).isEqualTo(1);
            assertThatThrownBy(() -> f.service.get(decision.operationId())).isInstanceOf(AccessDeniedException.class);
            when(f.principals.authorizeHistoryIndex("owner", true)).thenReturn(f.grant);
            assertThatThrownBy(() -> f.service.archive(decision.operationId())).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void projectionAdmissionChecksTheRequestedPrivilegeBeforeEvaluatingTheGitPrecondition() throws Exception {
        try (var f = new Fixture()) {
            var projection = mock(DerivedProjection.class);
            f.service.configureDerivedProjection(projection);
            for (var command : java.util.List.of(DerivedProjection.Kind.RELATION, DerivedProjection.Kind.VECTOR_PURGE)) {
                boolean destructive = command == DerivedProjection.Kind.VECTOR_PURGE;
                when(f.principals.authorizeHistoryIndex("owner", destructive))
                        .thenThrow(new AccessDeniedException("Projection grant revoked"));
                for (String precondition : java.util.Arrays.asList(null, "invalid", "a".repeat(40))) {
                    assertThatThrownBy(() -> f.service.submitProjection(f.scope, command, precondition, null, null))
                            .isInstanceOf(AccessDeniedException.class);
                }
            }
            verifyNoInteractions(projection);
        }
    }

    @Test void projectionReplayRetainsItsResultAndRequiresBothOriginalPreconditionAndCurrentMaintainer() throws Exception {
        try (var f = new Fixture()) {
            var port = mock(DerivedProjection.class);
            f.service.configureDerivedProjection(port);
            when(f.members.canMaintain(f.catalogueRow, "owner")).thenReturn(true);
            String head = "a".repeat(40);
            var original = new DerivedProjection.Source("selected", "first");
            var result = new DerivedProjection.Result("published", "original-model", 2, 1, null, null, null);
            when(port.capture(f.scope, DerivedProjection.Kind.VECTOR_BUILD, head)).thenReturn(original);
            when(port.apply(eq(f.scope), eq(DerivedProjection.Kind.VECTOR_BUILD), eq(head), eq(original), anyString(), any()))
                    .thenAnswer(call -> { call.getArgument(5, Runnable.class).run(); return result; });
            when(port.describe(result)).thenReturn("retained-result");
            when(port.readResult("retained-result")).thenReturn(result);
            var accepted = f.service.submitProjection(f.scope, DerivedProjection.Kind.VECTOR_BUILD, head, "identity", null);
            var replay = f.service.submitProjection(f.scope, DerivedProjection.Kind.VECTOR_BUILD, head, "identity", null);
            assertThat(replay).isEqualTo(accepted);
            assertThat(replay.result()).isEqualTo(result);
            String operationId = replay.receipt().operationId();
            assertThat(f.service.projectionStatus(f.scope, operationId)).isEqualTo(replay.receipt());
            assertThat(f.service.retryProjection(f.scope, operationId)).isEqualTo(replay.receipt());
            var other = new RepositoryContext(f.scope.repositoryId(), f.scope.workspaceId(), "another", f.scope.username(), f.scope.scope());
            assertThatThrownBy(() -> f.service.projectionStatus(other, operationId))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThatThrownBy(() -> f.service.submitProjection(f.scope, DerivedProjection.Kind.VECTOR_BUILD,
                    "b".repeat(40), "identity", null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            when(f.members.canMaintain(f.catalogueRow, "owner")).thenReturn(false);
            assertThatThrownBy(() -> f.service.submitProjection(f.scope, DerivedProjection.Kind.VECTOR_BUILD, head,
                    "identity", null)).isInstanceOf(AccessDeniedException.class);
            verify(port, times(1)).apply(eq(f.scope), eq(DerivedProjection.Kind.VECTOR_BUILD), eq(head), eq(original), anyString(), any());
            verify(port, times(1)).capture(f.scope, DerivedProjection.Kind.VECTOR_BUILD, head);
            assertThatThrownBy(() -> f.service.retryProjection(f.scope, operationId)).isInstanceOf(AccessDeniedException.class);
            when(f.members.canMaintain(f.catalogueRow, "owner")).thenReturn(true);
            when(f.principals.authorizeHistoryIndex("owner", true)).thenReturn(f.grant);
            assertThat(f.service.archiveProjection(f.scope, operationId).archivedAt()).isNotNull();
        }
    }

    @Test void derivedRecoveryKeepsTheOriginalSourceAndRejectsAContributorWithoutMaintenanceAuthority() throws Exception {
        try (var f = new Fixture()) {
            var port = mock(DerivedProjection.class);
            f.service.configureDerivedProjection(port);
            String head = "b".repeat(40);
            assertThatThrownBy(() -> f.service.submitProjection(f.scope, DerivedProjection.Kind.RELATION, head, "select", null))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(port);
            when(f.members.canMaintain(f.catalogueRow, "owner")).thenReturn(true);
            var retained = new DerivedProjection.Source("model-A", "original-generation");
            when(port.capture(f.scope, DerivedProjection.Kind.VECTOR_BUILD, head)).thenReturn(retained);
            when(port.apply(eq(f.scope), eq(DerivedProjection.Kind.VECTOR_BUILD), eq(head), eq(retained), anyString(), any()))
                    .thenThrow(new DerivedProjection.SourceUnavailable(new java.io.IOException("interrupted")))
                    .thenThrow(new DerivedProjection.SourceChanged("replacement generation"));
            var admission = f.service.submitProjection(f.scope, DerivedProjection.Kind.VECTOR_BUILD, head, "interrupted", null);
            assertThat(admission.receipt().phase()).isEqualTo("RETRYABLE");
            f.jdbc.update("update history_index_operation set due_at=0 where operation_id=?", admission.receipt().operationId());
            f.service.recover();
            var receipt = f.service.get(admission.receipt().operationId());
            assertThat(receipt.phase()).isEqualTo("MANUAL_REVIEW");
            assertThat(receipt.actor()).isEqualTo(f.grant.principalId());
            assertThat(receipt.executor()).isEqualTo("system:history-index");
            verify(port).capture(f.scope, DerivedProjection.Kind.VECTOR_BUILD, head);
        }
    }

    @Test void failedProjectionDecisionIsDurableAndCannotStarveAnotherDueCommand() throws Exception {
        try (var f = new Fixture()) {
            var port = mock(DerivedProjection.class);
            f.service.configureDerivedProjection(port);
            String head = "a".repeat(40);
            when(f.members.canMaintain(f.catalogueRow, "owner")).thenReturn(true);
            var source = new DerivedProjection.Source(null, null);
            var evidence = new DerivedProjection.Result(null, null, 0, 3, null, null, null, "CORRUPT", head, head);
            var completed = new DerivedProjection.Result(null, null, 0, 1, 0, 0, 0);
            when(port.capture(f.scope, DerivedProjection.Kind.RELATION, head)).thenReturn(source);
            doThrow(new DerivedProjection.DecisionRequired("VERIFICATION_FAILED", false, evidence, null))
                    .doReturn(completed).when(port).apply(eq(f.scope), eq(DerivedProjection.Kind.RELATION), eq(head),
                            eq(source), anyString(), any());
            when(port.describe(evidence)).thenReturn("retained-verification");
            when(port.describe(completed)).thenReturn("success");
            when(port.readResult("retained-verification")).thenReturn(evidence);
            var first = f.service.submitProjection(f.scope, DerivedProjection.Kind.RELATION, head, "failure", null);
            assertThat(first.receipt().phase()).isEqualTo("MANUAL_REVIEW");
            assertThat(first.result()).isEqualTo(evidence);
            assertThat(f.service.submitProjection(f.scope, DerivedProjection.Kind.RELATION, head, "failure", null)).isEqualTo(first);
            var next = f.journal.admit(f.scope, f.scope.branch(), HistoryIndexJournal.Kind.RELATION, f.grant, "other", null,
                    () -> new HistoryIndexJournal.Capture(head, null));
            f.service.recover();
            assertThat(f.service.get(next.receipt().operationId()).phase()).isEqualTo("COMPLETED");
        }
    }

    static final class Fixture implements AutoCloseable {
        final RepositoryContext scope;
        final EntityManager entities = mock(EntityManager.class);
        final JdbcTemplate jdbc;
        final Grant grant = new Grant("stable-owner", "LOCAL", "local-binding");
        final DslGitRepository git = new DslGitRepository();
        final HistoryIndexGrant principals = mock(HistoryIndexGrant.class);
        final SystemRepository catalogueRow = new SystemRepository();
        final RepositoryMembershipService members = mock(RepositoryMembershipService.class);
        final com.taxonomy.versioning.repository.ArchitectureCommitIndexRepository indexRows =
                mock(com.taxonomy.versioning.repository.ArchitectureCommitIndexRepository.class);
        final HistoryIndexJournal journal;
        final HistoryIndexMaintenance service;

        Fixture() throws Exception { this(RepositoryContext.centralRead("repo", "draft", "owner")); }
        Fixture(RepositoryContext context) throws Exception {
            scope = context;
            var db = new DriverManagerDataSource("jdbc:hsqldb:mem:history-service-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
            var migration = new org.springframework.core.io.ClassPathResource("db/restore/hsqldb/V29__history_index_recovery.sql");
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(migration).execute(db);
            jdbc = new JdbcTemplate(db);
            journal = new HistoryIndexJournal(jdbc, new DataSourceTransactionManager(db), Clock.systemUTC());
            var resolver = mock(WorkspaceResolver.class);
            when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
            when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(scope);
            when(principals.currentActor("owner")).thenReturn(grant.principalId());
            when(principals.authorizeHistoryIndex("owner", false)).thenReturn(grant);
            var factory = mock(DslGitRepositoryFactory.class);
            when(factory.resolveRepository(scope)).thenReturn(git);
            var membershipFence = mock(jakarta.persistence.Query.class);
            when(entities.createQuery(startsWith("update RepositoryMembership"))).thenReturn(membershipFence);
            when(membershipFence.setParameter("repository", "repo")).thenReturn(membershipFence);
            when(membershipFence.setParameter("actor", "owner")).thenReturn(membershipFence);
            var catalogue = mock(SystemRepositoryRepository.class);
            when(catalogue.lockForMutation("repo")).thenReturn(1);
            when(catalogue.findByRepositoryId("repo")).thenReturn(Optional.of(catalogueRow));
            when(members.canContribute(catalogueRow, "owner")).thenReturn(true);
            service = new HistoryIndexMaintenance(journal, principals, resolver, factory, catalogue, members,
                    entities, new CommitIndexService(factory, indexRows));
        }
        HistoryIndexJournal.Operation admit(String key, String captured) {
            return journal.admit(scope, "draft", REBUILD, grant, key, null, () -> new HistoryIndexJournal.Capture(captured, null));
        }
        @Override public void close() { git.close(); }
    }
}
