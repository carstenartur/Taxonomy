package com.taxonomy.workspace.service;

import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
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
            process.createWorkspace(base, local, java.util.UUID.fromString(ALICE));
            process.loginAsWorkspaceOwner();
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
            // The scheduler runs without a request login. Only the fenced original journal actor is present.
            restarted.recovery.recoverDue();
            restarted.loginAsWorkspaceOwner();
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

    private enum ForkRecovery {
        TRACKING_RETAINED("COMPLETED"), TRACKING_ADVANCED("COMPLETED"),
        SOURCE_ACCESS_REVOKED("DENIED"), TRACKING_REPLACED("MANUAL_REVIEW"),
        DIFFERENT_OPERATION("MANUAL_REVIEW"), OTHER_FETCH_DESCENDANT_REPLACED("MANUAL_REVIEW");

        final String phase;
        ForkRecovery(String phase) { this.phase = phase; }
    }

    @ParameterizedTest
    @EnumSource(ForkRecovery.class)
    void reopenedForkFetchSettlesOnlyItsRetainedAuthorizedPublication(ForkRecovery recovery) throws Exception {
        String url = "jdbc:hsqldb:file:" + directory.resolve("fork-rewritten-" + recovery) + ";shutdown=true";
        var clock = new WorkspaceFetchJournalTest.MutableClock();
        var fork = RepositoryContext.centralWrite("fork", "main", "alice");
        var tracking = new GitUpstreamRef("source", "main");
        String base, original, local, operation, publishedSource;
        boolean otherFetch = recovery == ForkRecovery.DIFFERENT_OPERATION
                || recovery == ForkRecovery.OTHER_FETCH_DESCENDANT_REPLACED;
        try (var first = new Process(url, clock)) {
            var source = first.persistence.repositories.getCentralRepository("source");
            var target = first.persistence.repositories.getCentralRepository("fork");
            base = source.commitDsl("main", "base", "alice", "fork point");
            original = source.commitDsl("main", "reviewed", "alice", "incoming");
            first.forkMetadata.setForkPointCommit(base);
            local = target.commitDsl("main", "private fork", "alice", "fork branch");
            target.createBranchAtCommit("review", local);
            GitHistoryTransfer.copyCommitClosure(source, target, base, List.of());
            tracking.advance(target, null, base, "alice");

            var request = new WorkspaceUpstreamService.Request(original, base);
            if (otherFetch) {
                doThrow(new TransientDataAccessResourceException("Stopped before Git publication")).doNothing()
                        .when(first.actors).checkPublication(WorkspaceFetchJournalTest.GRANT, "alice", RepositoryScope.CENTRAL_WRITE, false);
            } else {
                first.interruptReceiptAt = original;
                if (recovery == ForkRecovery.TRACKING_RETAINED) {
                    var advanceOnce = new AtomicBoolean(true);
                    doAnswer(ignored -> {
                        if (advanceOnce.getAndSet(false))
                            source.commitDsl("main", "advanced after admission", "alice", "next source revision");
                        return null;
                    }).when(first.actors).checkPublication(WorkspaceFetchJournalTest.GRANT, "alice", RepositoryScope.CENTRAL_WRITE, false);
                }
            }
            var receipt = first.recovery.fetch(fork, request, "reviewed", null);
            operation = receipt.operationId();
            assertThat(receipt.result()).isNull();
            var pending = first.journal.status(fork, ALICE, operation);
            assertThat(pending.phase()).isEqualTo("RETRYABLE");
            assertThat(pending.observedSourceCommit()).isEqualTo(original);
            publishedSource = source.getHeadCommit("main");
            if (recovery == ForkRecovery.TRACKING_RETAINED) assertThat(publishedSource).isNotEqualTo(original);
            if (otherFetch) {
                assertThat(tracking.head(target)).isEqualTo(base);
                var separate = first.recovery.fetch(fork, request, "separate-fetch", null);
                assertThat(separate.operationId()).isNotEqualTo(operation);
                assertThat(separate.result().trackedCommit()).isEqualTo(original);
            }
            assertThat(tracking.head(target)).isEqualTo(original);
        }
        clock.now += Duration.ofMinutes(1).toMillis();
        try (var restarted = new Process(url, clock)) {
            restarted.forkMetadata.setForkPointCommit(base);
            var source = restarted.persistence.repositories.getCentralRepository("source");
            var target = restarted.persistence.repositories.getCentralRepository("fork");
            String retained = original;
            if (recovery == ForkRecovery.TRACKING_ADVANCED || recovery == ForkRecovery.OTHER_FETCH_DESCENDANT_REPLACED) {
                retained = source.commitDsl("main", "later", "alice", "another source revision");
                restarted.recovery.fetch(fork, new WorkspaceUpstreamService.Request(retained, original), "later", null);
            }
            String rewritten = rewriteMain(source);
            if (recovery == ForkRecovery.SOURCE_ACCESS_REVOKED) {
                // The initial authorization check passes. The publication acknowledgement must recheck it.
                var permitted = new AtomicBoolean(true);
                restarted.canContributeToSource = () -> permitted.getAndSet(false);
            }
            if (recovery == ForkRecovery.TRACKING_REPLACED || recovery == ForkRecovery.OTHER_FETCH_DESCENDANT_REPLACED) {
                // The tracking ref changes when the recovery rechecks current contributor access.
                var firstCheck = new AtomicBoolean(true);
                restarted.canContributeToSource = () -> {
                    if (!firstCheck.getAndSet(false)) {
                        try { forceRef(target, tracking.name(), local); }
                        catch (IOException failure) { throw new UncheckedIOException(failure); }
                    }
                    return true;
                };
                retained = local;
            }

            restarted.recovery.recoverDue();
            var status = restarted.journal.status(fork, ALICE, operation);
            assertThat(status.phase()).isEqualTo(recovery.phase);
            assertThat(status.attempts()).isEqualTo(2);
            assertThat(status.requestedCommit()).isEqualTo(original);
            if ("COMPLETED".equals(recovery.phase)) {
                assertThat(status.observedSourceCommit()).isEqualTo(publishedSource);
                assertThat(status.resultCommit()).isEqualTo(original);
                var result = restarted.recovery.retry(fork, operation).result();
                assertThat(result.sourceCommit()).isEqualTo(publishedSource);
                assertThat(result.trackedCommit()).isEqualTo(original);
            } else {
                assertThat(status.observedSourceCommit()).isEqualTo(original);
                assertThat(status.resultCommit()).isNull();
            }
            assertThat(tracking.head(target)).isEqualTo(retained);
            assertThat(target.getHeadCommit("main")).isEqualTo(local);
            assertThat(target.getHeadCommit("review")).isEqualTo(local);
            assertThat(source.getHeadCommit("main")).isEqualTo(rewritten);
        }
    }

    @Test void reusedForkFetchKeyCannotClaimPublicationFromAnExpiredReceipt() throws Exception {
        String url = "jdbc:hsqldb:file:" + directory.resolve("fork-expired") + ";shutdown=true";
        var clock = new WorkspaceFetchJournalTest.MutableClock();
        var fork = RepositoryContext.centralWrite("fork", "main", "alice");
        var tracking = new GitUpstreamRef("source", "main");
        String base, original, local, operation;
        try (var first = new Process(url, clock)) {
            var source = first.persistence.repositories.getCentralRepository("source");
            var target = first.persistence.repositories.getCentralRepository("fork");
            base = source.commitDsl("main", "base", "alice", "fork point");
            original = source.commitDsl("main", "reviewed", "alice", "incoming");
            first.forkMetadata.setForkPointCommit(base);
            local = target.commitDsl("main", "private fork", "alice", "fork branch");
            GitHistoryTransfer.copyCommitClosure(source, target, base, List.of());
            tracking.advance(target, null, base, "alice");

            var request = new WorkspaceUpstreamService.Request(original, base);
            var completed = first.recovery.fetch(fork, request, "reused", null);
            operation = completed.operationId();
            assertThat(completed.result().trackedCommit()).isEqualTo(original);
            var previous = first.recovery.archive(fork, operation);
            clock.now += WorkspaceFetchJournal.ARCHIVE_MILLIS + 1;
            first.journal.expireDue();
            assertThatThrownBy(() -> first.journal.status(fork, ALICE, operation)).hasMessageContaining("404");

            // Even the original timestamp can repeat after the system clock is corrected.
            clock.now = previous.createdAt();
            doThrow(new TransientDataAccessResourceException("Stopped before Git publication"))
                    .when(first.actors).checkPublication(WorkspaceFetchJournalTest.GRANT, "alice", RepositoryScope.CENTRAL_WRITE, false);
            var retained = first.recovery.fetch(fork, request, "reused", null);
            assertThat(retained.operationId()).isEqualTo(operation);
            assertThat(retained.result()).isNull();
            var pending = first.journal.status(fork, ALICE, operation);
            assertThat(pending.phase()).isEqualTo("RETRYABLE");
            assertThat(pending.createdAt()).isEqualTo(previous.createdAt());
            rewriteMain(source);
        }
        clock.now += Duration.ofMinutes(1).toMillis();
        try (var restarted = new Process(url, clock)) {
            restarted.forkMetadata.setForkPointCommit(base);
            restarted.recovery.recoverDue();
            var status = restarted.recovery.status(fork, operation);
            assertThat(status.phase()).isEqualTo("MANUAL_REVIEW");
            assertThat(status.resultCommit()).isNull();
            var target = restarted.persistence.repositories.getCentralRepository("fork");
            assertThat(tracking.head(target)).isEqualTo(original);
            assertThat(target.getHeadCommit("main")).isEqualTo(local);
        }
    }

    private static String rewriteMain(DslGitRepository source) throws IOException {
        String rewritten = source.commitDsl("replacement", "unrelated", "alice", "unrelated history");
        forceRef(source, "refs/heads/main", rewritten);
        return rewritten;
    }

    private static void forceRef(DslGitRepository repository, String ref, String commit) throws IOException {
        var replace = repository.getGitRepository().updateRef(ref);
        replace.setExpectedOldObjectId(repository.readFreshRef(ref).getObjectId());
        replace.setNewObjectId(ObjectId.fromString(commit));
        assertThat(replace.forceUpdate()).isEqualTo(RefUpdate.Result.FORCED);
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

    @Test void foregroundFetchReturnsItsReceiptWhenCurrentAuthorizationMustBeRenewed() throws Exception {
        var clock = new WorkspaceFetchJournalTest.MutableClock();
        String url = "jdbc:hsqldb:file:" + directory.resolve("renew-login") + ";shutdown=true";
        try (var process = new Process(url, clock)) {
            var source = process.persistence.repositories.getCentralRepository("source");
            var target = process.persistence.repositories.openWorkspaceRepository("w1");
            String original = source.commitDsl("main", "reviewed", "alice", "incoming");
            String local = target.commitDsl("draft", "working", "alice", "unpublished");
            process.createWorkspace(original, local, java.util.UUID.fromString(ALICE));
            process.loginAsWorkspaceOwner();
            doThrow(new WorkspaceFetchAuthority.FreshLoginRequired()).doNothing()
                    .when(process.actors).checkPublication(WorkspaceFetchJournalTest.GRANT, "alice", RepositoryScope.WORKSPACE, false);

            var pending = process.recovery.fetch(CONTEXT, new WorkspaceUpstreamService.Request(original, null), "renew", null);
            assertThat(pending.result()).isNull();
            var status = process.recovery.status(CONTEXT, pending.operationId());
            assertThat(status.phase()).isEqualTo("AWAITING_AUTHORIZATION");
            assertThat(process.journal.due()).isEmpty();
            assertThat(new GitUpstreamRef("source", "main").head(target)).isNull();

            var completed = process.recovery.retry(CONTEXT, pending.operationId());
            assertThat(completed.operationId()).isEqualTo(pending.operationId());
            assertThat(completed.result().trackedCommit()).isEqualTo(original);
            assertThat(target.getHeadCommit("draft")).isEqualTo(local);
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
        String interruptReceiptAt;
        BooleanSupplier canContributeToSource = () -> true;
        Process(String url, WorkspaceFetchJournalTest.MutableClock clock) {
            persistence = new EditorPersistenceFixture(url, CentralFetchOperation.class, WorkspaceFetchOperation.class, UserWorkspace.class);
            em = SharedEntityManagerCreator.createSharedEntityManager(persistence.factory);
            var manager = new JpaTransactionManager(persistence.factory) {
                @Override protected void doCommit(DefaultTransactionStatus status) {
                    try {
                        if (interruptReceiptAt != null && interruptReceiptAt.equals(new GitUpstreamRef("source", "main")
                                .head(persistence.repositories.getCentralRepository("fork")))) {
                            interruptReceiptAt = null;
                            super.doRollback(status);
                            throw new UnexpectedRollbackException("Injected lost fork tracking acknowledgement");
                        }
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                    super.doCommit(status);
                }
            };
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
            when(memberships.canContribute(metadata, "alice")).thenAnswer(ignored -> canContributeToSource.getAsBoolean());
            when(actors.authorize("alice", RepositoryScope.WORKSPACE)).thenReturn(WorkspaceFetchJournalTest.GRANT);
            when(actors.currentActor("alice")).thenReturn(ALICE);
            upstream = new WorkspaceUpstreamService(rows, catalogue, memberships, persistence.repositories);
            forks = new CentralForkUpstreamService(catalogue,memberships,persistence.repositories);
            recovery = new WorkspaceFetchRecovery(journal, upstream, actors, forks);
        }
        void loginAsWorkspaceOwner() {
            var identity = mock(WorkspaceActorAccess.class, CALLS_REAL_METHODS);
            doReturn(Optional.of(java.util.UUID.fromString(ALICE))).when(identity).currentOwnerPrincipal("alice");
            upstream.configureWorkspaceActors(identity);
        }

        void createWorkspace(String base, String local) {
            createWorkspace(base, local, null);
        }

        void createWorkspace(String base, String local, java.util.UUID principalId) {
            var workspace = new UserWorkspace();
            if (principalId != null) workspace.assignOwnerPrincipal(principalId);
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
