package com.taxonomy.workspace.service;

import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.taxonomy.workspace.service.WorkspaceFetchJournalTest.ALICE;
import static com.taxonomy.workspace.service.WorkspaceFetchJournalTest.CONTEXT;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceFetchRecoveryRestartTest {
    @TempDir Path directory;

    @Test void restoresTheOriginalFetchAfterGitPublicationAndDatabaseRollback() throws Exception {
        String url = "jdbc:hsqldb:file:" + directory.resolve("fetch") + ";shutdown=true";
        var clock = new WorkspaceFetchJournalTest.MutableClock();
        String original;
        String local;
        String operation;
        try (var process = new Process(url, clock)) {
            var source = process.persistence.repositories.getCentralRepository("source");
            var target = process.persistence.repositories.openWorkspaceRepository("w1");
            String base = source.commitDsl("main", "base", "alice", "base");
            original = source.commitDsl("main", "reviewed", "alice", "incoming");
            local = target.commitDsl("draft", "unpublished", "alice", "local");
            process.createWorkspace(base, local);
            process.failBookkeeping.set(true);
            var receipt = process.recovery.fetch(CONTEXT, new WorkspaceUpstreamService.Request(original, null), "review", null);
            assertThat(receipt.result()).isNull();
            assertThat(new GitUpstreamRef("source", "main").head(target)).isEqualTo(original);
            assertThat(process.workspace(false).orElseThrow().getLastFetchedCommit()).isNull();
            operation = process.tx.execute(status -> process.em.createQuery("select f.id from WorkspaceFetchOperation f", String.class).getSingleResult());
            assertThat(receipt.operationId()).isEqualTo(operation);
            assertThat(process.journal.status(CONTEXT, ALICE, operation).phase()).isEqualTo("RETRYABLE");
            assertThat(process.journal.due()).isEmpty();
        }
        clock.now += Duration.ofMinutes(1).toMillis();
        try (var restarted = new Process(url, clock)) {
            var source = restarted.persistence.repositories.getCentralRepository("source");
            var target = restarted.persistence.repositories.openWorkspaceRepository("w1");
            String later = source.commitDsl("main", "advanced", "alice", "after crash");
            assertThat(restarted.journal.due()).containsExactly(operation);
            restarted.recovery.recoverDue();
            var status = restarted.recovery.status(CONTEXT, operation);
            assertThat(status.phase()).isEqualTo("COMPLETED");
            assertThat(status.attempts()).isEqualTo(2);
            assertThat(status.requestedCommit()).isEqualTo(original);
            assertThat(status.resultCommit()).isEqualTo(original);
            assertThat(status.observedSourceCommit()).isEqualTo(later);
            assertThat(restarted.workspace(false).orElseThrow().getLastFetchedCommit()).isEqualTo(original);
            assertThat(restarted.workspace(false).orElseThrow().getCurrentCommit()).isEqualTo(local);
            assertThat(target.getHeadCommit("draft")).isEqualTo(local);
            assertThat(new GitUpstreamRef("source", "main").head(target)).isEqualTo(original);
            String executor = restarted.tx.execute(s -> restarted.em.find(WorkspaceFetchOperation.class, operation).executor);
            assertThat(executor).isEqualTo("system:workspace-fetch-recovery");
        }
    }

    @Test void disabledOriginalPrincipalStopsTheOrphanBeforeTrackingRefPublication() throws Exception {
        String url = "jdbc:hsqldb:file:" + directory.resolve("denied") + ";shutdown=true";
        var clock = new WorkspaceFetchJournalTest.MutableClock();
        try (var process = new Process(url, clock)) {
            var source = process.persistence.repositories.getCentralRepository("source");
            var target = process.persistence.repositories.openWorkspaceRepository("w1");
            String incoming = source.commitDsl("main", "pending", "alice", "incoming");
            String local = target.commitDsl("draft", "private", "alice", "local");
            process.createWorkspace(incoming, local);
            var id = process.journal.admit(CONTEXT, WorkspaceFetchJournalTest.GRANT, process.upstream.capture(CONTEXT),
                    new WorkspaceUpstreamService.Request(incoming, null), null, null).operationId();
            process.journal.claim(id, "terminated", false);
            clock.now += Duration.ofMinutes(6).toMillis();
            doThrow(new AccessDeniedException("Inactive binding")).when(process.actors).checkPublication(WorkspaceFetchJournalTest.GRANT, "alice", true);
            process.recovery.recoverDue();
            assertThat(process.journal.status(CONTEXT, ALICE, id).phase()).isEqualTo("DENIED");
            assertThat(process.journal.status(CONTEXT, ALICE, id).decisionCode()).isEqualTo("AUTHORITY_CHANGED");
            assertThat(new GitUpstreamRef("source", "main").head(target)).isNull();
            assertThat(target.getHeadCommit("draft")).isEqualTo(local);
            assertThat(process.journal.due()).isEmpty();
        }
    }

    @Test void aReplacedForkPointCannotAuthorizeAnEarlierOrphan() throws Exception {
        var clock = new WorkspaceFetchJournalTest.MutableClock();
        String url = "jdbc:hsqldb:file:" + directory.resolve("fork-point") + ";shutdown=true";
        try (var process = new Process(url, clock)) {
            var source = process.persistence.repositories.getCentralRepository("source");
            var target = process.persistence.repositories.openWorkspaceRepository("w1");
            String originalBase = source.commitDsl("main", "base", "alice", "fork point");
            String incoming = source.commitDsl("main", "incoming", "alice", "selected");
            String local = target.commitDsl("draft", "unrelated", "alice", "unpublished");
            process.createWorkspace(originalBase, local);
            var captured = process.journal.admit(CONTEXT, WorkspaceFetchJournalTest.GRANT, process.upstream.capture(CONTEXT),
                    new WorkspaceUpstreamService.Request(incoming, null), "reviewed", null);
            assertThat(captured.originalForkPoint()).isEqualTo(originalBase);
            process.journal.claim(captured.operationId(), "terminated", false);
            process.tx.executeWithoutResult(status -> process.workspace(true).orElseThrow().setBaseCommit(incoming));
            clock.now += WorkspaceFetchJournal.LEASE_MILLIS + 1;
            process.recovery.recoverDue();
            var result = process.recovery.status(CONTEXT, captured.operationId());
            assertThat(result.phase()).isEqualTo("MANUAL_REVIEW");
            assertThat(result.originalForkPoint()).isEqualTo(originalBase);
            assertThat(new GitUpstreamRef("source", "main").head(target)).isNull();
            assertThat(target.getHeadCommit("draft")).isEqualTo(local);
        }
    }

    @Test void advancedTrackingNeedsRetainedAncestryToSettleAnOrphan() throws Exception {
        for (boolean descendant : List.of(true, false)) {
            var clock = new WorkspaceFetchJournalTest.MutableClock();
            String url = "jdbc:hsqldb:file:" + directory.resolve("tracking-" + descendant) + ";shutdown=true";
            try (var process = new Process(url, clock)) {
                var source = process.persistence.repositories.getCentralRepository("source");
                var target = process.persistence.repositories.openWorkspaceRepository("w1");
                String requested = source.commitDsl("main", "reviewed", "alice", "incoming");
                String later = source.commitDsl("main", "advanced", "alice", "later");
                String local = target.commitDsl("draft", "unrelated", "alice", "unpublished");
                process.createWorkspace(requested, local);
                String id = process.journal.admit(CONTEXT, WorkspaceFetchJournalTest.GRANT, process.upstream.capture(CONTEXT),
                        new WorkspaceUpstreamService.Request(requested, null), "review", null).operationId();
                process.journal.claim(id, "lost-process", false);
                GitHistoryTransfer.copyCommitClosure(source, target, later, List.of());
                var tracking = new GitUpstreamRef("source", "main");
                String replacement = descendant ? later : local;
                tracking.advance(target, null, replacement, "successor");
                clock.now += WorkspaceFetchJournal.LEASE_MILLIS + 1;
                process.recovery.recoverDue();
                var view = process.recovery.status(CONTEXT, id);
                assertThat(view.phase()).isEqualTo(descendant ? "SUPERSEDED" : "MANUAL_REVIEW");
                assertThat(view.resultCommit()).isEqualTo(descendant ? later : null);
                assertThat(view.requestedCommit()).isEqualTo(requested);
                assertThat(tracking.head(target)).isEqualTo(replacement);
                assertThat(target.getHeadCommit("draft")).isEqualTo(local);
                assertThat(process.journal.due()).isEmpty();
            }
        }
    }

    private static class Process implements AutoCloseable {
        final EditorPersistenceFixture persistence;
        final EntityManager em;
        final TransactionTemplate tx;
        final WorkspaceFetchJournal journal;
        final WorkspaceFetchRecovery recovery;
        final WorkspaceUpstreamService upstream;
        final WorkspaceFetchAuthority actors = mock(WorkspaceFetchAuthority.class);
        final AtomicBoolean failBookkeeping = new AtomicBoolean();
        Process(String url, WorkspaceFetchJournalTest.MutableClock clock) {
            persistence = new EditorPersistenceFixture(url, WorkspaceFetchOperation.class, UserWorkspace.class);
            em = SharedEntityManagerCreator.createSharedEntityManager(persistence.factory);
            var manager = new JpaTransactionManager(persistence.factory);
            tx = new TransactionTemplate(manager);
            journal = new WorkspaceFetchJournal(em, manager, clock);
            var rows = mock(UserWorkspaceRepository.class);
            when(rows.findByWorkspaceId("w1")).thenAnswer(ignored -> workspace(false));
            when(rows.findByWorkspaceIdForUpdate("w1")).thenAnswer(ignored -> workspace(true));
            when(rows.save(any(UserWorkspace.class))).thenAnswer(call -> {
                var row = call.<UserWorkspace>getArgument(0);
                if (failBookkeeping.getAndSet(false)) throw new IllegalStateException("Injected bookkeeping loss");
                return em.merge(row);
            });
            var metadata = new SystemRepository();
            var catalogue = mock(SystemRepositoryService.class);
            when(catalogue.getRepository("source")).thenReturn(metadata);
            doAnswer(call -> { call.<Consumer<SystemRepository>>getArgument(1).accept(metadata); return null; })
                    .when(catalogue).readCurrentAuthorization(eq("source"), any());
            var memberships = mock(RepositoryMembershipService.class);
            when(memberships.canContribute(metadata, "alice")).thenReturn(true);
            when(actors.authorize("alice")).thenReturn(WorkspaceFetchJournalTest.GRANT);
            when(actors.currentActor("alice")).thenReturn(ALICE);
            upstream = new WorkspaceUpstreamService(rows, catalogue, memberships, persistence.repositories);
            recovery = new WorkspaceFetchRecovery(journal, upstream, actors);
        }
        void createWorkspace(String base, String local) {
            var workspace = new UserWorkspace();
            workspace.setWorkspaceId("w1"); workspace.setUsername("alice"); workspace.setCreatedAt(Instant.now());
            workspace.setSourceRepositoryId("source"); workspace.setSourceBranch("main");
            workspace.setBaseCommit(base); workspace.setCurrentCommit(local); workspace.setLastIntegratedCommit(base);
            tx.executeWithoutResult(status -> em.persist(workspace));
        }
        Optional<UserWorkspace> workspace(boolean locked) {
            return tx.execute(status -> {
                var query = em.createQuery("select w from UserWorkspace w where w.workspaceId='w1'", UserWorkspace.class);
                if (locked) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
                return query.getResultList().stream().findFirst();
            });
        }
        @Override public void close() { persistence.close(); }
    }
}
