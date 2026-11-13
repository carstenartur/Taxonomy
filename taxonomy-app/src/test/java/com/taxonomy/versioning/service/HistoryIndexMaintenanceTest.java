package com.taxonomy.versioning.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
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
    void backgroundRecoveryRechecksTheInitiatingGrantAndSeparatesTheWorker() throws Exception {
        try (var f = new Fixture()) {
            String head = f.git.commitDsl("draft","initial","owner","first");
            var admitted = f.admit("revoked", head);
            doThrow(new AccessDeniedException("removed grant")).when(f.principals).verify(f.grant, "owner", false, true);
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

    static final class Fixture implements AutoCloseable {
        final RepositoryContext scope = RepositoryContext.centralRead("repo", "draft", "owner");
        final Grant grant = new Grant("stable-owner", "LOCAL", "local-binding");
        final DslGitRepository git = new DslGitRepository();
        final HistoryIndexGrant principals = mock(HistoryIndexGrant.class);
        final SystemRepository catalogueRow = new SystemRepository();
        final RepositoryMembershipService members = mock(RepositoryMembershipService.class);
        final com.taxonomy.versioning.repository.ArchitectureCommitIndexRepository indexRows =
                mock(com.taxonomy.versioning.repository.ArchitectureCommitIndexRepository.class);
        final HistoryIndexJournal journal;
        final HistoryIndexMaintenance service;

        Fixture() throws Exception {
            var db = new DriverManagerDataSource("jdbc:hsqldb:mem:history-service-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
            var migration = new org.springframework.core.io.ClassPathResource("db/restore/hsqldb/V29__history_index_recovery.sql");
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(migration).execute(db);
            journal = new HistoryIndexJournal(new JdbcTemplate(db), new DataSourceTransactionManager(db), Clock.systemUTC());
            var resolver = mock(WorkspaceResolver.class);
            when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
            when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(scope);
            when(principals.currentActor("owner")).thenReturn(grant.principalId());
            when(principals.authorize("owner", false)).thenReturn(grant);
            var factory = mock(DslGitRepositoryFactory.class);
            when(factory.resolveRepository(scope)).thenReturn(git);
            var catalogue = mock(SystemRepositoryRepository.class);
            when(catalogue.lockForMutation("repo")).thenReturn(1);
            when(catalogue.findByRepositoryId("repo")).thenReturn(Optional.of(catalogueRow));
            when(members.canContribute(catalogueRow, "owner")).thenReturn(true);
            service = new HistoryIndexMaintenance(journal, principals, resolver, factory, catalogue, members,
                    mock(EntityManager.class), new CommitIndexService(factory, indexRows));
        }
        HistoryIndexJournal.Operation admit(String key, String captured) {
            return journal.admit(scope, "draft", REBUILD, grant, key, null, () -> new HistoryIndexJournal.Capture(captured, null));
        }
        @Override public void close() { git.close(); }
    }
}
