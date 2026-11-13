package com.taxonomy.workspace.service;

import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.RepositoryMembershipRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.repository.WorkspaceProvisioningFenceImpl;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import com.taxonomy.workspace.signing.GitCommitSignatureService;
import com.taxonomy.workspace.signing.SignatureStatus;
import com.taxonomy.workspace.signing.SigningTestKeys;
import com.taxonomy.workspace.storage.DslCommit;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceOriginalHistoryTest {
    private static final String OWNER = "architect";
    private static final String BRANCH = "main";

    @TempDir Path directory;

    @Test
    void personalWorkingCopyRetainsSignedHistoryAfterSourceDeletionAndRestart() throws Exception {
        String database = "jdbc:hsqldb:file:" + directory.resolve("personal-copy") + ";shutdown=true";
        var signatures = SigningTestKeys.createService(directory.resolve("keys"));
        String parent;
        String captured;
        String workspaceId;
        org.eclipse.jgit.lib.ObjectId tagId;
        byte[] annotation;
        try (var fixture = new Fixture(database, signatures)) {
            var source = fixture.createSource();
            var sourceId = source.getRepositoryId();
            var git = fixture.git.getCentralRepository(sourceId);
            parent = git.commitDsl(BRANCH, "first architecture", OWNER, "Parent");
            captured = git.commitDsl(BRANCH, "second architecture", OWNER, "Selected");
            var copies = new RepositoryWorkspaceService(fixture.rows, fixture.catalog, fixture.git, fixture.memberships);
            workspaceId = copies.createWorkingCopy(OWNER, sourceId, BRANCH, "Working copy", null).getWorkspaceId();
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getGitRepository())) {
                var tag = org.eclipse.jgit.api.Git.wrap(git.getGitRepository()).tag().setName("reviewed/v1")
                        .setAnnotated(true).setMessage("Reviewed version").setTagger(new org.eclipse.jgit.lib.PersonIdent(OWNER, "owner@example.test"))
                        .setObjectId(walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(parent))).call();
                tagId = tag.getObjectId();
            }
            annotation = git.getGitRepository().open(tagId).getBytes();
            var upstream = new WorkspaceUpstreamService(fixture.rows, fixture.catalog, fixture.memberships, fixture.git);
            var selected = RepositoryContext.workspace(sourceId, workspaceId, "draft", OWNER);
            upstream.fetchTag(selected, new WorkspaceUpstreamService.TagRequest("reviewed/v1", tagId.name(), captured));
            fixture.git.deleteCentralRepository(sourceId);
            assertNull(fixture.git.getCentralRepository(sourceId).getHeadCommit(BRANCH));
        }
        try (var fixture = new Fixture(database, new GitCommitSignatureService(
                SigningTestKeys.verifierProperties(directory.resolve("keys"))))) {
            var workspace = fixture.git.openWorkspaceRepository(workspaceId);
            var metadata = fixture.rows.findByWorkspaceId(workspaceId).orElseThrow();
            assertEquals(captured, metadata.getBaseCommit());
            assertEquals(captured, metadata.getCurrentCommit());
            assertEquals(captured, workspace.getHeadCommit("draft"));
            assertEquals(captured, workspace.getHeadCommit("sync-base"));
            assertEquals(captured, new GitUpstreamRef(
                    metadata.getSourceRepositoryId(), BRANCH).head(workspace));
            assertEquals(List.of(captured, parent),
                    workspace.getDslHistory("draft").stream().map(DslCommit::commitId).toList());
            assertEquals("first architecture", workspace.getDslAtCommit(parent));
            assertEquals(SignatureStatus.VERIFIED, workspace.verifyCommit(captured).status());
            assertEquals(SignatureStatus.VERIFIED, workspace.verifyCommit(parent).status());
            assertEquals(tagId, workspace.getGitRepository().exactRef("refs/tags/reviewed/v1").getObjectId());
            assertArrayEquals(annotation, workspace.getGitRepository().open(tagId).getBytes());
        }
    }

    @Test
    void interruptedTransferRetriesTheDurableForkPointAndSurvivesSourceDeletion() throws Exception {
        String database = "jdbc:hsqldb:file:" + directory.resolve("provisioning") + ";shutdown=true";
        var signatures = SigningTestKeys.createService(directory.resolve("keys"));
        String sourceId;
        String workspaceId;
        String ancestor;
        String captured;
        try (var fixture = new Fixture(database, signatures)) {
            var source = fixture.createSource();
            sourceId = source.getRepositoryId();
            var git = fixture.git.getCentralRepository(sourceId);
            ancestor = git.commitDsl(BRANCH, "first architecture", OWNER, "Parent");
            captured = git.commitDsl(BRANCH, "second architecture", OWNER, "Selected checkpoint");
            var workspace = fixture.manager(fixture.git).createWorkspace(OWNER, "Architecture", null);
            workspaceId = workspace.getWorkspaceId();
            workspace.setSourceRepositoryId(sourceId);
            fixture.rows.save(workspace);

            var interrupted = spy(fixture.git);
            doAnswer(call -> {
                var pinned = fixture.rows.findByWorkspaceId(workspaceId).orElseThrow();
                assertEquals(WorkspaceProvisioningStatus.PROVISIONING, pinned.getProvisioningStatus());
                assertEquals(captured, pinned.getBaseCommit());
                assertEquals(BRANCH, pinned.getSourceBranch());
                assertEquals(BRANCH, pinned.getCurrentBranch());
                assertNull(pinned.getCurrentCommit());
                throw new IllegalStateException("Interrupted before opening destination");
            }).when(interrupted).openWorkspaceRepository(workspaceId);
            var failure = assertThrows(RuntimeException.class,
                    () -> fixture.manager(interrupted).provisionWorkspaceRepository(OWNER, workspaceId));
            assertEquals("Interrupted before opening destination", failure.getCause().getMessage());
            assertEquals(WorkspaceProvisioningStatus.FAILED,
                    fixture.rows.findByWorkspaceId(workspaceId).orElseThrow().getProvisioningStatus());
            assertNull(fixture.git.openWorkspaceRepository(workspaceId).getHeadCommit(BRANCH));
            git.commitDsl(BRANCH, "third architecture", OWNER, "Source advanced");
        }
        try (var fixture = new Fixture(database, signatures)) {
            var manager = fixture.manager(fixture.git);
            var ready = manager.provisionWorkspaceRepository(OWNER, workspaceId);
            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertEquals(captured, ready.getCurrentCommit());
            assertEquals(captured, manager.provisionWorkspaceRepository(OWNER, workspaceId).getCurrentCommit());
            fixture.git.deleteCentralRepository(sourceId);
            assertNull(fixture.git.getCentralRepository(sourceId).getHeadCommit(BRANCH));
        }
        var verifier = new GitCommitSignatureService(SigningTestKeys.verifierProperties(directory.resolve("keys")));
        try (var fixture = new Fixture(database, verifier)) {
            var workspace = fixture.git.openWorkspaceRepository(workspaceId);
            assertEquals(captured, workspace.getHeadCommit(BRANCH));
            assertEquals(captured, new GitUpstreamRef(sourceId, BRANCH).head(workspace));
            assertEquals(List.of(captured, ancestor), workspace.getDslHistory(BRANCH).stream().map(DslCommit::commitId).toList());
            assertEquals("first architecture", workspace.getDslAtCommit(ancestor));
            assertEquals("second architecture", workspace.getDslAtHead(BRANCH));
            assertEquals(SignatureStatus.VERIFIED, workspace.verifyCommit(ancestor).status());
            assertEquals(SignatureStatus.VERIFIED, workspace.verifyCommit(captured).status());
            assertEquals(captured, fixture.rows.findByWorkspaceId(workspaceId).orElseThrow().getCurrentCommit());
        }
    }

    @Test
    void anExpiredCrashLeaseResumesTheSameCapturedCommitAndFencesTheOldWorker() throws Exception {
        String database = "jdbc:hsqldb:file:" + directory.resolve("orphan") + ";shutdown=true";
        String workspaceId;
        String captured;
        String oldWorker;
        try (var fixture = new Fixture(database, GitCommitSignatureService.disabled())) {
            var source = fixture.createSource();
            var git = fixture.git.getCentralRepository(source.getRepositoryId());
            captured = git.commitDsl(BRANCH, "captured architecture", OWNER, "Captured checkpoint");
            var row = fixture.manager(fixture.git).createWorkspace(OWNER, "Crash recovery", null);
            row.setSourceRepositoryId(source.getRepositoryId());
            workspaceId = row.getWorkspaceId();
            fixture.rows.save(row);
            var interrupted = spy(fixture.git);
            doThrow(new AssertionError("Process stopped")).when(interrupted).openWorkspaceRepository(workspaceId);
            assertThrows(AssertionError.class,
                    () -> fixture.manager(interrupted).provisionWorkspaceRepository(OWNER, workspaceId));
            var abandoned = fixture.rows.findByWorkspaceId(workspaceId).orElseThrow();
            oldWorker = abandoned.getProvisioningLeaseOwner();
            assertNotNull(oldWorker);
            assertEquals(WorkspaceProvisioningStatus.PROVISIONING, abandoned.getProvisioningStatus());
            assertEquals(captured, abandoned.getBaseCommit());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> fixture.manager(fixture.git).provisionWorkspaceRepository(OWNER, workspaceId));
            git.commitDsl(BRANCH, "later architecture", OWNER, "Source advanced");
            fixture.transactions.executeWithoutResult(status -> {
                var em = SharedEntityManagerCreator.createSharedEntityManager(fixture.persistence.factory);
                em.createQuery("update UserWorkspace w set w.provisioningLeaseUntil = :expired where w.workspaceId = :id")
                        .setParameter("expired", java.time.Instant.EPOCH).setParameter("id", workspaceId).executeUpdate();
            });
        }
        try (var fixture = new Fixture(database, GitCommitSignatureService.disabled())) {
            var manager = fixture.manager(fixture.git);
            assertEquals(captured, manager.provisionWorkspaceRepository(OWNER, workspaceId).getCurrentCommit());
            var ready = fixture.rows.findByWorkspaceId(workspaceId).orElseThrow();
            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertEquals(captured, fixture.git.openWorkspaceRepository(workspaceId).getHeadCommit(BRANCH));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> fixture.rows.withProvisioningLease(ready, oldWorker,
                            current -> { fail("The retired worker must not publish or clean up"); }));
        }
    }

    @Test
    void legacyInProgressRowsGetOnePersistedRecoveryWindowOnFirstObservation() throws Exception {
        String database = "jdbc:hsqldb:mem:legacy-claim-" + java.util.UUID.randomUUID();
        try (var fixture = new Fixture(database, GitCommitSignatureService.disabled())) {
            var manager = fixture.manager(fixture.git);
            var row = manager.createWorkspace(OWNER, "Legacy worker", null);
            row.setProvisioningStatus(WorkspaceProvisioningStatus.PROVISIONING);
            fixture.rows.save(row);
            String legacyId = row.getWorkspaceId();
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> manager.provisionWorkspaceRepository(OWNER, legacyId));
                var after = fixture.rows.findByWorkspaceId(row.getWorkspaceId()).orElseThrow();
                assertNotNull(after.getProvisioningLeaseUntil());
                if (attempt == 0) {
                    assertTrue(after.getProvisioningLeaseUntil().isAfter(java.time.Instant.now().plusSeconds(840)));
                    row = after;
                } else {
                    assertEquals(row.getProvisioningLeaseUntil(), after.getProvisioningLeaseUntil());
                }
                assertEquals(0, after.getProvisioningAttempt());
            }
        }
    }

    @Test
    void aResumedWorkerCannotDeleteTheWorkingCopyCompletedByItsSuccessor() throws Exception {
        String database = "jdbc:hsqldb:mem:paused-allocation-" + java.util.UUID.randomUUID();
        try (var fixture = new Fixture(database, GitCommitSignatureService.disabled());
             var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var source = fixture.createSource();
            String sourceId = source.getRepositoryId();
            var git = fixture.git.getCentralRepository(sourceId);
            String captured = git.commitDsl(BRANCH, "original", OWNER, "Selected checkpoint");
            var entered = new java.util.concurrent.CompletableFuture<String>();
            var resume = new java.util.concurrent.CountDownLatch(1);
            var paused = spy(fixture.git);
            doAnswer(call -> {
                entered.complete(call.getArgument(0));
                if (!resume.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Lost successor");
                return call.callRealMethod();
            }).when(paused).openWorkspaceRepository(anyString());
            var copies = new RepositoryWorkspaceService(fixture.rows, fixture.catalog, paused, fixture.memberships);
            var original = pool.submit(() -> copies.createWorkingCopy(OWNER, sourceId, BRANCH, "Concurrent copy", null));
            String id = entered.get(30, java.util.concurrent.TimeUnit.SECONDS);
            try {
                fixture.transactions.executeWithoutResult(status -> {
                    var em = SharedEntityManagerCreator.createSharedEntityManager(fixture.persistence.factory);
                    em.createQuery("update UserWorkspace w set w.provisioningLeaseUntil = :expired where w.workspaceId = :id")
                            .setParameter("expired", java.time.Instant.EPOCH).setParameter("id", id).executeUpdate();
                });
                git.commitDsl(BRANCH, "newer", OWNER, "Source advanced after capture");
                var ready = fixture.manager(fixture.git).provisionWorkspaceRepository(OWNER, id);
                assertEquals(captured, ready.getCurrentCommit());
                assertEquals(2, ready.getProvisioningAttempt());
            } finally {
                resume.countDown();
            }
            var lost = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> original.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(org.springframework.web.server.ResponseStatusException.class, lost.getCause());
            verify(paused, never()).deleteWorkspaceRepository(anyString());
            var target = fixture.git.openWorkspaceRepository(id);
            assertEquals(captured, target.getHeadCommit("draft"));
            assertEquals(captured, target.getHeadCommit("sync-base"));
            assertEquals(captured, new GitUpstreamRef(sourceId, BRANCH).head(target));
            assertEquals(WorkspaceProvisioningStatus.READY,
                    fixture.rows.findByWorkspaceId(id).orElseThrow().getProvisioningStatus());
        }
    }

    @Test
    void aLostInitialSaveAcknowledgementCannotReplaceACommittedSuccessor() throws Exception {
        String database = "jdbc:hsqldb:mem:lost-ack-" + java.util.UUID.randomUUID();
        try (var fixture = new Fixture(database, GitCommitSignatureService.disabled())) {
            var source = fixture.createSource();
            String sourceId = source.getRepositoryId();
            String captured = fixture.git.getCentralRepository(sourceId).commitDsl(BRANCH, "original", OWNER, "Checkpoint");
            var id = new java.util.concurrent.atomic.AtomicReference<String>();
            var interrupted = mock(UserWorkspaceRepository.class, org.mockito.AdditionalAnswers.delegatesTo(fixture.rows));
            doAnswer(call -> {
                var saved = fixture.rows.save(call.<UserWorkspace>getArgument(0));
                if (id.compareAndSet(null, saved.getWorkspaceId())) {
                    fixture.transactions.executeWithoutResult(status -> {
                        var em = SharedEntityManagerCreator.createSharedEntityManager(fixture.persistence.factory);
                        em.createQuery("update UserWorkspace w set w.provisioningLeaseUntil = :expired where w.workspaceId = :id")
                                .setParameter("expired", java.time.Instant.EPOCH).setParameter("id", saved.getWorkspaceId()).executeUpdate();
                    });
                    fixture.manager(fixture.git).provisionWorkspaceRepository(OWNER, saved.getWorkspaceId());
                    throw new IllegalStateException("Initial persistence acknowledgement lost");
                }
                return saved;
            }).when(interrupted).save(any(UserWorkspace.class));
            var creator = new RepositoryWorkspaceService(interrupted, fixture.catalog, fixture.git, fixture.memberships);
            assertThrows(IllegalStateException.class,
                    () -> creator.createWorkingCopy(OWNER, sourceId, BRANCH, "Lost acknowledgment", null));
            var retained = fixture.rows.findByWorkspaceId(id.get()).orElseThrow();
            assertEquals(WorkspaceProvisioningStatus.READY, retained.getProvisioningStatus());
            assertEquals(2, retained.getProvisioningAttempt());
            assertEquals(captured, retained.getCurrentCommit());
            assertEquals(captured, fixture.git.openWorkspaceRepository(id.get()).getHeadCommit("draft"));
        }
    }

    @Test
    void aJoinedCallerGetsTheCurrentAttemptEvenWhenItsEntityWasLoadedBeforeTheBulkClaim() throws Exception {
        String database = "jdbc:hsqldb:mem:outer-claim-" + java.util.UUID.randomUUID();
        try (var fixture = new Fixture(database, GitCommitSignatureService.disabled())) {
            var source = fixture.createSource();
            fixture.git.getCentralRepository(source.getRepositoryId()).commitDsl(BRANCH, "original", OWNER, "Checkpoint");
            var manager = fixture.manager(fixture.git);
            var row = manager.createWorkspace(OWNER, "Outer transaction", null);
            row.setSourceRepositoryId(source.getRepositoryId());
            fixture.rows.save(row);
            fixture.transactions.executeWithoutResult(status -> {
                assertEquals(0, fixture.rows.findByWorkspaceId(row.getWorkspaceId()).orElseThrow().getProvisioningAttempt());
                var ready = manager.provisionWorkspaceRepository(OWNER, row.getWorkspaceId());
                assertEquals(1, ready.getProvisioningAttempt());
                assertNotNull(ready.getProvisioningLeaseOwner());
                assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            });
            assertEquals(1, fixture.rows.findByWorkspaceId(row.getWorkspaceId()).orElseThrow().getProvisioningAttempt());
        }
    }

    @Test
    void revokedSourceContributionDoesNotPublishTheImportedCommit() throws Exception {
        String database = "jdbc:hsqldb:file:" + directory.resolve("revocation") + ";shutdown=true";
        try (var fixture = new Fixture(database, GitCommitSignatureService.disabled())) {
            String actor = "contributor";
            var source = fixture.createSource();
            String sourceId = source.getRepositoryId();
            fixture.transactions.executeWithoutResult(status ->
                    fixture.memberships.assignRole(sourceId, actor, RepositoryRole.CONTRIBUTOR, OWNER));
            var git = fixture.git.getCentralRepository(sourceId);
            String captured = git.commitDsl(BRANCH, "architecture", OWNER, "Selected checkpoint");
            var workspace = fixture.manager(fixture.git).createWorkspace(actor, "Private copy", null);
            String workspaceId = workspace.getWorkspaceId();
            workspace.setSourceRepositoryId(sourceId);
            fixture.rows.save(workspace);

            var repositories = spy(fixture.git);
            doAnswer(call -> {
                fixture.transactions.executeWithoutResult(status -> fixture.memberships.removeMembership(source, actor, OWNER));
                return call.callRealMethod();
            }).when(repositories).openWorkspaceRepository(workspaceId);

            assertThrows(AccessDeniedException.class,
                    () -> fixture.manager(repositories).provisionWorkspaceRepository(actor, workspaceId));
            var retained = fixture.rows.findByWorkspaceId(workspaceId).orElseThrow();
            assertEquals(WorkspaceProvisioningStatus.FAILED, retained.getProvisioningStatus());
            assertEquals(captured, retained.getBaseCommit());
            assertNull(retained.getCurrentCommit());
            assertNull(fixture.git.openWorkspaceRepository(workspaceId).getHeadCommit(BRANCH));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final EditorPersistenceFixture persistence;
        final DslGitRepositoryFactory git;
        final TransactionTemplate transactions;
        final SystemRepositoryService catalog;
        final RepositoryMembershipService memberships;
        final UserWorkspaceRepository rows;

        Fixture(String database, GitCommitSignatureService signatures) {
            persistence = new EditorPersistenceFixture(database, SystemRepository.class, UserWorkspace.class, RepositoryMembership.class);
            var transactionManager = new JpaTransactionManager(persistence.factory);
            transactions = new TransactionTemplate(transactionManager);
            var entities = SharedEntityManagerCreator.createSharedEntityManager(persistence.factory);
            var repositories = new JpaRepositoryFactory(entities);
            catalog = new SystemRepositoryService(repositories.getRepository(SystemRepositoryRepository.class), entities);
            memberships = new RepositoryMembershipService(repositories.getRepository(RepositoryMembershipRepository.class));
            git = new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(persistence.factory), catalog, signatures);
            var proxy = new ProxyFactory(repositories.getRepository(UserWorkspaceRepository.class,
                    RepositoryFragments.just(new WorkspaceProvisioningFenceImpl(entities))));
            proxy.addAdvice(new TransactionInterceptor(transactionManager, new MatchAlwaysTransactionAttributeSource()));
            rows = (UserWorkspaceRepository) proxy.getProxy();
        }

        SystemRepository createSource() {
            return transactions.execute(status -> {
                var source = catalog.createCentralRepository("Original source", "original-source", null,
                        RepositoryVisibility.PRIVATE, OWNER, BRANCH);
                return catalog.markProvisioningReady(source.getRepositoryId());
            });
        }

        WorkspaceManager manager(DslGitRepositoryFactory repositories) {
            var manager = new WorkspaceManager(rows, 50, catalog, repositories);
            manager.configureSourceMemberships(memberships);
            return manager;
        }

        @Override public void close() throws Exception {
            try (persistence; git) {
                // Close the Git handles before the Hibernate factory.
            }
        }
    }
}
