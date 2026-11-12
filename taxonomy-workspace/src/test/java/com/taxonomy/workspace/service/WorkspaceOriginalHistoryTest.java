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
import com.taxonomy.workspace.signing.GitCommitSignatureService;
import com.taxonomy.workspace.signing.SignatureStatus;
import com.taxonomy.workspace.signing.SigningTestKeys;
import com.taxonomy.workspace.storage.DslCommit;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
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
        try (var fixture = new Fixture(database, signatures)) {
            var source = fixture.createSource();
            var sourceId = source.getRepositoryId();
            var git = fixture.git.getCentralRepository(sourceId);
            parent = git.commitDsl(BRANCH, "first architecture", OWNER, "Parent");
            captured = git.commitDsl(BRANCH, "second architecture", OWNER, "Selected");
            var copies = new RepositoryWorkspaceService(fixture.rows, fixture.catalog, fixture.git, fixture.memberships);
            workspaceId = copies.createWorkingCopy(OWNER, sourceId, BRANCH, "Working copy", null).getWorkspaceId();
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
            assertEquals(List.of(captured, parent),
                    workspace.getDslHistory("draft").stream().map(DslCommit::commitId).toList());
            assertEquals("first architecture", workspace.getDslAtCommit(parent));
            assertEquals(SignatureStatus.VERIFIED, workspace.verifyCommit(captured).status());
            assertEquals(SignatureStatus.VERIFIED, workspace.verifyCommit(parent).status());
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
            assertEquals(List.of(captured, ancestor), workspace.getDslHistory(BRANCH).stream().map(DslCommit::commitId).toList());
            assertEquals("first architecture", workspace.getDslAtCommit(ancestor));
            assertEquals("second architecture", workspace.getDslAtHead(BRANCH));
            assertEquals(SignatureStatus.VERIFIED, workspace.verifyCommit(ancestor).status());
            assertEquals(SignatureStatus.VERIFIED, workspace.verifyCommit(captured).status());
            assertEquals(captured, fixture.rows.findByWorkspaceId(workspaceId).orElseThrow().getCurrentCommit());
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
            catalog = new SystemRepositoryService(repositories.getRepository(SystemRepositoryRepository.class));
            memberships = new RepositoryMembershipService(repositories.getRepository(RepositoryMembershipRepository.class));
            git = new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(persistence.factory), catalog, signatures);
            var proxy = new ProxyFactory(repositories.getRepository(UserWorkspaceRepository.class));
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
