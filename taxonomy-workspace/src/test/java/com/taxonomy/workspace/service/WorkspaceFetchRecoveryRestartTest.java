package com.taxonomy.workspace.service;

import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.RepositoryVisibility;
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
import java.util.function.BiFunction;

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
            doThrow(new AccessDeniedException("Inactive binding")).when(process.actors).checkPublication(WorkspaceFetchJournalTest.GRANT, "alice", RepositoryScope.WORKSPACE, true);
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

    @Test
    void reopenedForkFetchAndTwoSameNamedTagsKeepSeparateOriginalAuthority() throws Exception {
        String url = "jdbc:hsqldb:file:" + directory.resolve("central-and-tags") + ";shutdown=true";
        var clock = new WorkspaceFetchJournalTest.MutableClock();
        var fork = RepositoryContext.centralWrite("fork", "main", "alice");
        var tracking = new GitUpstreamRef("source", "main");
        String base, incoming, localFork, localCopy, fetchId, forkTagId, workspaceTagId, tagObject;
        try (var first = new Process(url, clock)) {
            var source = first.persistence.repositories.getCentralRepository("source");
            var destination = first.persistence.repositories.getCentralRepository("fork");
            var working = first.persistence.repositories.openWorkspaceRepository("w1");
            base = source.commitDsl("main", "original", "alice", "initial");
            incoming = source.commitDsl("main", "incoming", "alice", "next");
            first.forkMetadata.setForkPointCommit(base);
            localFork = destination.commitDsl("main", "private fork", "alice", "fork branch");
            localCopy = working.commitDsl("draft", "private work", "alice", "local draft");
            first.createWorkspace(base,localCopy);
            first.recovery.fetch(CONTEXT,new WorkspaceUpstreamService.Request(incoming,null),"working-fetch",null);
            var selected = first.forks.capture(fork);
            fetchId = first.journal.admit(fork,WorkspaceFetchJournalTest.GRANT,selected,
                    new WorkspaceUpstreamService.Request(incoming,null),"fork-fetch",null).operationId();
            assertThat(first.journal.claim(fetchId,"interrupted-before-copy",true)).isNotNull();
            assertThat(tracking.head(destination)).isNull();
        }
        clock.now += WorkspaceFetchJournal.LEASE_MILLIS + 1;
        try (var second = new Process(url,clock)) {
            second.forkMetadata.setForkPointCommit(base);
            second.recovery.recoverDue();
            var status = second.recovery.status(fork,fetchId);
            assertThat(status.phase()).isEqualTo("COMPLETED");
            assertThat(status.attempts()).isEqualTo(2);
            assertThat(status.sourceRepositoryId()).isEqualTo("source");
            assertThat(status.resultCommit()).isEqualTo(incoming);
            assertThat(status.workspaceId()).isNull();
            var source = second.persistence.repositories.getCentralRepository("source");
            var destination = second.persistence.repositories.getCentralRepository("fork");
            assertThat(tracking.head(destination)).isEqualTo(incoming);
            assertThat(destination.getHeadCommit("main")).isEqualTo(localFork);
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(source.getGitRepository())) {
                tagObject = org.eclipse.jgit.api.Git.wrap(source.getGitRepository()).tag().setName("same-name")
                        .setMessage("original tag").setObjectId(walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(base)))
                        .setTagger(new org.eclipse.jgit.lib.PersonIdent("Alice","alice@example.test")).call().getObjectId().name();
            }
            var tag = new WorkspaceUpstreamService.TagRequest("same-name",tagObject,incoming);
            workspaceTagId = second.journal.admitTag(CONTEXT,WorkspaceFetchJournalTest.GRANT,second.upstream.capture(CONTEXT),
                    tag,"same-key",null).operationId();
            forkTagId = second.journal.admitTag(fork,WorkspaceFetchJournalTest.GRANT,second.forks.capture(fork),
                    tag,"same-key",null).operationId();
            assertThat(workspaceTagId).isNotEqualTo(forkTagId);
            assertThat(second.journal.claim(workspaceTagId,"lost-workspace-reply",true)).isNotNull();
            assertThat(second.journal.claim(forkTagId,"stopped-before-tag",true)).isNotNull();
            // The Git store acknowledges only the workspace tag. The transaction owner then disappears.
            second.tx.executeWithoutResult(statusTransaction -> {
                try { second.upstream.fetchTag(CONTEXT,tag); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            org.eclipse.jgit.api.Git.wrap(source.getGitRepository()).tagDelete().setTags("same-name").call();
        }
        clock.now += WorkspaceFetchJournal.LEASE_MILLIS + 1;
        try (var third = new Process(url,clock)) {
            third.forkMetadata.setForkPointCommit(base);
            third.recovery.recoverDue();
            var saved = third.recovery.status(CONTEXT,workspaceTagId);
            assertThat(saved.phase()).isEqualTo("COMPLETED");
            assertThat(saved.attempts()).isEqualTo(2);
            assertThat(saved.resultTagObject()).isEqualTo(tagObject);
            var conflicting = third.recovery.status(fork,forkTagId);
            assertThat(conflicting.phase()).isEqualTo("MANUAL_REVIEW");
            assertThat(conflicting.resultTagObject()).isNull();
            assertThat(third.persistence.repositories.getCentralRepository("fork").getGitRepository().exactRef("refs/tags/same-name")).isNull();
            assertThat(third.persistence.repositories.openWorkspaceRepository("w1").getHeadCommit("draft")).isEqualTo(localCopy);
            assertThat(third.persistence.repositories.getCentralRepository("fork").getHeadCommit("main")).isEqualTo(localFork);
            assertThat(third.journal.due()).isEmpty();
        }
    }

    private static class Process implements AutoCloseable {
        final EditorPersistenceFixture persistence;
        final EntityManager em;
        final TransactionTemplate tx;
        final WorkspaceFetchJournal journal;
        final WorkspaceFetchRecovery recovery;
        final WorkspaceUpstreamService upstream;
        final CentralForkUpstreamService forks;
        final SystemRepository forkMetadata = new SystemRepository();
        final WorkspaceFetchAuthority actors = mock(WorkspaceFetchAuthority.class);
        final AtomicBoolean failBookkeeping = new AtomicBoolean();
        Process(String url, WorkspaceFetchJournalTest.MutableClock clock) {
            persistence = new EditorPersistenceFixture(url, CentralFetchOperation.class, WorkspaceFetchOperation.class, UserWorkspace.class);
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
            metadata.setVisibility(RepositoryVisibility.PRIVATE);
            forkMetadata.setVisibility(RepositoryVisibility.PRIVATE);
            forkMetadata.setUpstreamRepositoryId("source");
            forkMetadata.setUpstreamBranch("main");
            doAnswer(call -> call.<BiFunction<SystemRepository,SystemRepository,?>>getArgument(1).apply(forkMetadata,metadata))
                    .when(catalogue).readCurrentForkAuthorization(eq("fork"),any());
            var memberships = mock(RepositoryMembershipService.class);
            when(memberships.canContribute(forkMetadata, "alice")).thenReturn(true);
            when(actors.authorize("alice", RepositoryScope.CENTRAL_WRITE)).thenReturn(WorkspaceFetchJournalTest.GRANT);
            when(memberships.canContribute(metadata, "alice")).thenReturn(true);
            when(actors.authorize("alice", RepositoryScope.WORKSPACE)).thenReturn(WorkspaceFetchJournalTest.GRANT);
            when(actors.currentActor("alice")).thenReturn(ALICE);
            upstream = new WorkspaceUpstreamService(rows, catalogue, memberships, persistence.repositories);
            forks = new CentralForkUpstreamService(catalogue,memberships,persistence.repositories);
            recovery = new WorkspaceFetchRecovery(journal, upstream, actors, forks);
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
