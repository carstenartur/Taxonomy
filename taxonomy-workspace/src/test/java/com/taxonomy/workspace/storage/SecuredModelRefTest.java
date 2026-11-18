package com.taxonomy.workspace.storage;

import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.workspace.service.RepositoryRefPolicy;
import com.taxonomy.workspace.signing.GitCommitSignatureService;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import jakarta.persistence.LockModeType;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecuredModelRefTest {
    @TempDir Path temp;

    @Test
    void retainedModelHandlesCheckTheCurrentPolicyForNormalForcedAndDeletedRefs() throws Exception {
        var denied = new AtomicBoolean();
        var observed = new ArrayList<String>();
        try (var database = database("operations");
             var factory = guarded(database, (session, name, branch, operation) -> {
                 assertThat(session.getTransaction().isActive()).isTrue();
                 observed.add(name + "/" + branch + "/" + operation);
                 if (denied.get()) throw new AccessDeniedException("Policy changed");
             })) {
            var git = factory.openWorkspaceRepository("owned");
            assertThat(git.isDatabaseBacked()).isTrue();
            String base = git.commitDsl("proposal", "# base", "alice", "original");
            String next = git.commitDsl("proposal", "# next", "alice", "next");

            denied.set(true);
            assertThatThrownBy(() -> git.commitDsl("proposal", "# denied", "alice", "denied"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(git.getHeadCommit("proposal")).isEqualTo(next);
            assertThatThrownBy(() -> replace(git, "proposal", next, base, true))
                    .isInstanceOf(IOException.class).hasRootCauseInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> git.deleteBranch("proposal", next, "alice"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(git.getHeadCommit("proposal")).isEqualTo(next);
            assertThat(observed).contains("ws-owned/proposal/UPDATE", "ws-owned/proposal/FORCE", "ws-owned/proposal/DELETE");

            denied.set(false);
            assertThat(replace(git, "proposal", next, base, true)).isEqualTo(RefUpdate.Result.FORCED);
            git.deleteBranch("proposal", base, "alice");
            assertThat(git.getHeadCommit("proposal")).isNull();
        }
    }

    @Test
    void directJgitBatchRejectsTheWholeBatchWhenOneExactBranchIsProtected() throws Exception {
        var protectedBranch = new AtomicBoolean();
        try (var database = database("batch");
             var factory = guarded(database, (session, name, branch, operation) -> {
                 if (protectedBranch.get() && branch.equals("reviewed")) throw new AccessDeniedException("Protected branch");
             })) {
            var git = factory.openWorkspaceRepository("owned");
            String base = git.commitDsl("reviewed", "# base", "alice", "original");
            git.createBranch("neighbor", "reviewed");
            git.createBranch("candidate", "reviewed");
            String candidate = git.commitDsl("candidate", "# incoming", "alice", "incoming");
            var first = new ReceiveCommand(ObjectId.fromString(base), ObjectId.fromString(candidate), "refs/heads/neighbor");
            var second = new ReceiveCommand(ObjectId.fromString(base), ObjectId.fromString(candidate), "refs/heads/reviewed");
            protectedBranch.set(true);
            var batch = git.getGitRepository().getRefDatabase().newBatchUpdate();
            batch.setAtomic(true);
            batch.addCommand(List.of(first, second));
            try (var walk = new RevWalk(git.getGitRepository())) {
                batch.execute(walk, NullProgressMonitor.INSTANCE);
            }
            assertThat(first.getResult()).isNotEqualTo(ReceiveCommand.Result.OK);
            assertThat(second.getResult()).isNotEqualTo(ReceiveCommand.Result.OK);
            assertThat(git.getHeadCommit("neighbor")).isEqualTo(base);
            assertThat(git.getHeadCommit("reviewed")).isEqualTo(base);
        }
    }

    @Test
    void policyDecisionUsesTheSameNativeTransactionAsPublication() throws Exception {
        var failAudit = new AtomicBoolean();
        try (var database = database("audit")) {
            database.factory.inTransaction(session -> session.createNativeMutationQuery(
                    "create table policy_probe (name varchar(80) primary key)").executeUpdate());
            try (var factory = guarded(database, (session, name, branch, operation) -> {
                if (failAudit.get()) {
                    session.createNativeMutationQuery("insert into policy_probe(name) values ('rolled-back')")
                            .executeUpdate();
                    throw new AccessDeniedException("Required evidence could not be committed");
                }
            })) {
                var git = factory.openWorkspaceRepository("owned");
                String base = git.commitDsl("proposal", "# base", "alice", "base");
                failAudit.set(true);
                assertThatThrownBy(() -> git.commitDsl("proposal", "# later", "alice", "later"))
                        .isInstanceOf(AccessDeniedException.class);
                assertThat(git.getHeadCommit("proposal")).isEqualTo(base);
                try (var session = database.factory.openSession()) {
                    assertThat(session.createNativeQuery("select count(*) from policy_probe", Long.class).getSingleResult()).isZero();
                }
            }
        }
    }

    @Test
    void concurrentPolicyChangeWaitsForNativePublicationAndAppliesToTheNextWrite() throws Exception {
        var pause = new AtomicBoolean();
        var denied = new AtomicBoolean();
        var atPublication = new CountDownLatch(1);
        var finishPublication = new CountDownLatch(1);
        var policyStarted = new CountDownLatch(1);
        try (var first = database("race");
             var second = database("race");
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             var factory = guarded(first, (session, name, branch, operation) -> {
                 if (denied.get()) throw new AccessDeniedException("Protected branch");
                 if (pause.get()) {
                     atPublication.countDown();
                     await(finishPublication);
                 }
             })) {
            var git = factory.openWorkspaceRepository("owned");
            String base = git.commitDsl("proposal", "# base", "alice", "base");
            pause.set(true);
            var writer = executor.submit(() -> git.commitDslIfHeadMatches("proposal", base, "# next", "alice", "next"));
            assertThat(atPublication.await(10, TimeUnit.SECONDS)).isTrue();
            var policy = executor.submit(() -> {
                policyStarted.countDown();
                second.factory.inTransaction(session -> {
                    session.find(GitRepositoryLockEntity.class, "ws-owned", LockModeType.PESSIMISTIC_WRITE);
                    denied.set(true);
                });
            });
            assertThat(policyStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> policy.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            finishPublication.countDown();
            String next = writer.get(10, TimeUnit.SECONDS);
            policy.get(10, TimeUnit.SECONDS);
            assertThat(git.getHeadCommit("proposal")).isEqualTo(next);
            assertThatThrownBy(() -> git.commitDslIfHeadMatches("proposal", next, "# denied", "alice", "denied"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(git.getHeadCommit("proposal")).isEqualTo(next);
        } finally {
            finishPublication.countDown();
        }
    }

    private EditorPersistenceFixture database(String name) {
        return new EditorPersistenceFixture("jdbc:hsqldb:file:" + temp.resolve(name) + ";shutdown=true");
    }

    private static DslGitRepositoryFactory guarded(EditorPersistenceFixture database, RepositoryRefPolicy policy) {
        return new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(database.factory), null,
                GitCommitSignatureService.disabled(), database.factory, policy);
    }

    private static RefUpdate.Result replace(DslGitRepository git, String branch, String before, String after, boolean force)
            throws IOException {
        var update = git.getGitRepository().updateRef(Constants.R_HEADS + branch);
        update.setExpectedOldObjectId(ObjectId.fromString(before));
        update.setNewObjectId(ObjectId.fromString(after));
        update.setForceUpdate(force);
        return update.update();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Publication was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
