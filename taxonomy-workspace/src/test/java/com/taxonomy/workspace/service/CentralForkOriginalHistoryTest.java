package com.taxonomy.workspace.service;

import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.RepositoryMembershipRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.signing.GitCommitSignatureService;
import com.taxonomy.workspace.signing.SignatureStatus;
import com.taxonomy.workspace.signing.SigningTestKeys;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import com.taxonomy.workspace.storage.DslWorkspaceReadAdapter;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

class CentralForkOriginalHistoryTest {
    private static final String OWNER = "architect";
    private static final String BRANCH = "approved";

    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(value = RepositoryVisibility.class, names = {"PRIVATE", "PUBLIC"})
    void forkReopensAtTheSignedOriginalMergeAfterSourceStorageIsDeleted(RepositoryVisibility visibility) throws Exception {
        String database = "jdbc:hsqldb:file:" + directory.resolve("fork") + ";shutdown=true";
        var signer = SigningTestKeys.createService(directory.resolve("keys"));
        String sourceId, forkId, root, firstParent, secondParent, tip;
        try (var fixture = new Fixture(database, signer)) {
            var source = fixture.createSource(visibility);
            sourceId = source.getRepositoryId();
            var sourceGit = fixture.git.getCentralRepository(sourceId);
            root = sourceGit.commitDsl(BRANCH, "original root", OWNER, "Initial architecture");
            sourceGit.createBranchAtCommit("topic", root);
            firstParent = sourceGit.commitDsl(BRANCH, "approved architecture", OWNER, "Approved change");
            secondParent = sourceGit.commitDsl("topic", "topic architecture", OWNER, "Topic change");
            tip = sourceGit.commitMergeDslIfHeadMatches(BRANCH, firstParent, secondParent,
                    "merged architecture", OWNER, "Merged architecture", null);

            RepositoryVisibility requestedVisibility = visibility == RepositoryVisibility.PRIVATE ? null : visibility;
            var fork = fixture.service.createFork(sourceId, BRANCH, "Fork", "central-fork", null, requestedVisibility, OWNER);
            forkId = fork.getRepositoryId();
            assertEquals(RepositoryLifecycleState.ACTIVE, fork.getLifecycleState());
            assertEquals(visibility, fork.getVisibility());
            assertEquals(sourceId, fork.getUpstreamRepositoryId());
            assertEquals(BRANCH, fork.getUpstreamBranch());
            assertEquals(tip, fork.getForkPointCommit());
            assertEquals(tip, fixture.git.getCentralRepository(forkId).getHeadCommit(BRANCH));
            assertEquals(tip, sourceGit.getHeadCommit(BRANCH));

            fixture.git.deleteCentralRepository(sourceId);
            assertNull(fixture.git.getCentralRepository(sourceId).getHeadCommit(BRANCH));
        }

        var verifier = new GitCommitSignatureService(SigningTestKeys.verifierProperties(directory.resolve("keys")));
        try (var fixture = new Fixture(database, verifier)) {
            var fork = fixture.catalog.getRepository(forkId);
            assertEquals(tip, fork.getForkPointCommit());
            assertEquals(tip, new GitUpstreamRef(sourceId, BRANCH)
                    .head(fixture.git.getCentralRepository(forkId)));
            var reads = new DslWorkspaceReadAdapter(fixture.git);
            var forkRead = reads.openRead(RepositoryContext.centralRead(forkId, BRANCH, OWNER));
            var sourceRead = reads.openRead(RepositoryContext.centralRead(sourceId, BRANCH, OWNER));
            assertNull(sourceRead.currentHead());
            assertEquals(tip, forkRead.currentHead());
            assertEquals("original root", forkRead.dslAtCommit(root).orElseThrow());
            assertEquals("topic architecture", forkRead.dslAtCommit(secondParent).orElseThrow());
            assertEquals("merged architecture", forkRead.dslAtCommit(tip).orElseThrow());
            assertEquals(List.of(firstParent, secondParent), forkRead.commitMetadata(tip).parents());
            var forkGit = fixture.git.getCentralRepository(forkId);
            assertEquals(SignatureStatus.VERIFIED, forkGit.verifyCommit(root).status());
            assertEquals(SignatureStatus.VERIFIED, forkGit.verifyCommit(tip).status());
        }
    }

    enum ChangedAuthority { SOURCE_HEAD, CONTRIBUTOR_ROLE, SOURCE_VISIBILITY }

    @ParameterizedTest
    @EnumSource(ChangedAuthority.class)
    void authorityChangedAfterCaptureLeavesTheForkInactive(ChangedAuthority changed) throws Exception {
        String database = "jdbc:hsqldb:file:" + directory.resolve("authority") + ";shutdown=true";
        try (var fixture = new Fixture(database, GitCommitSignatureService.disabled())) {
            boolean publicSource = changed == ChangedAuthority.SOURCE_VISIBILITY;
            var source = fixture.createSource(publicSource ? RepositoryVisibility.PUBLIC : RepositoryVisibility.PRIVATE);
            String sourceId = source.getRepositoryId();
            String actor = "fork-contributor";
            fixture.memberships.assignRole(sourceId, actor, RepositoryRole.CONTRIBUTOR, OWNER);
            var sourceGit = fixture.git.getCentralRepository(sourceId);
            String branch = source.getDefaultBranch();
            String captured = sourceGit.commitDsl(branch, "source architecture", OWNER, "Selected source");
            var racingFactory = spy(fixture.git);
            doAnswer(call -> {
                var target = call.callRealMethod();
                assertEquals(captured, fixture.catalog.getRepository(call.getArgument(0)).getForkPointCommit());
                switch (changed) {
                    case SOURCE_HEAD -> sourceGit.commitDsl(branch, "changed architecture", OWNER, "Advance source");
                    case CONTRIBUTOR_ROLE -> fixture.memberships.removeMembership(source, actor, OWNER);
                    case SOURCE_VISIBILITY -> {
                        var current = fixture.catalog.getRepository(sourceId);
                        current.setVisibility(RepositoryVisibility.PRIVATE);
                        fixture.catalog.save(current);
                    }
                }
                return target;
            }).when(racingFactory).createCentralRepository(anyString(), anyString());
            var service = new ArchitectureRepositoryProvisioningService(fixture.catalog, fixture.memberships, racingFactory, namespace -> "meta { language: \"taxdsl\"; }\n");
            RepositoryVisibility visibility = publicSource ? RepositoryVisibility.PUBLIC : RepositoryVisibility.PRIVATE;
            org.junit.jupiter.api.function.Executable fork = () ->
                    service.createFork(sourceId, null, "Fork", "central-fork", null, visibility, actor);

            switch (changed) {
                case SOURCE_HEAD -> assertEquals(HttpStatus.CONFLICT,
                        assertThrows(ResponseStatusException.class, fork).getStatusCode());
                case CONTRIBUTOR_ROLE -> assertThrows(AccessDeniedException.class, fork);
                case SOURCE_VISIBILITY -> assertThrows(IllegalArgumentException.class, fork);
            }
            var failed = fixture.rows.findBySlug("central-fork").orElseThrow();
            assertEquals(RepositoryLifecycleState.FAILED, failed.getLifecycleState());
            assertEquals(captured, failed.getForkPointCommit());
            assertFalse(fixture.memberships.canRead(failed, actor));
            assertNull(fixture.git.getCentralRepository(failed.getRepositoryId()).getHeadCommit(branch));
        }
    }

    @Test
    void incrementalFetchAndSelectedTagSurviveSourceDeletionWithoutMovingForkBranches() throws Exception {
        String database = "jdbc:hsqldb:file:" + directory.resolve("incremental") + ";shutdown=true";
        var signer = SigningTestKeys.createService(directory.resolve("keys"));
        String sourceId, forkId, base, local, incoming, tag;
        try (var fixture = new Fixture(database, signer)) {
            var source = fixture.createSource(RepositoryVisibility.PRIVATE);
            sourceId = source.getRepositoryId();
            var origin = fixture.git.getCentralRepository(sourceId);
            base = origin.commitDsl(BRANCH, "initial", OWNER, "Initial");
            var fork = fixture.service.createFork(sourceId, BRANCH, "Fork", "incremental-fork", null, null, OWNER);
            forkId = fork.getRepositoryId();
            var target = fixture.git.getCentralRepository(forkId);
            local = target.commitDsl(BRANCH, "independent fork edits", OWNER, "Fork edit");
            incoming = origin.commitDsl(BRANCH, "new upstream architecture", OWNER, "Upstream edit");
            var service = new CentralForkUpstreamService(fixture.catalog, fixture.memberships, fixture.git);
            var status = service.inspect(forkId, OWNER);
            assertEquals(sourceId, status.sourceRepositoryId());
            assertEquals(incoming, status.sourceCommit());
            assertEquals(base, status.trackedCommit());
            var fetch = new WorkspaceUpstreamService.Request(incoming, base);
            assertEquals(incoming, service.fetch(forkId, OWNER, fetch).trackedCommit());
            assertEquals(incoming, service.fetch(forkId, OWNER, fetch).trackedCommit());
            assertThrows(BranchHeadConflictException.class, () -> service.fetch(forkId, OWNER,
                    new WorkspaceUpstreamService.Request(base, base)));
            try (var walk = new RevWalk(origin.getGitRepository())) {
                var commit = walk.parseCommit(ObjectId.fromString(incoming));
                var git = Git.wrap(origin.getGitRepository());
                git.tag().setName("omitted").setAnnotated(false).setObjectId(commit).call();
                tag = git.tag().setName("review/v1").setAnnotated(true).setMessage("Reviewed upstream")
                        .setTagger(new PersonIdent(OWNER, "owner@example.test")).setObjectId(commit).call().getObjectId().name();
            }
            assertEquals(tag, service.inspectTag(forkId, OWNER, "review/v1").sourceTagObject());
            var selection = new WorkspaceUpstreamService.TagRequest("review/v1", tag, incoming);
            assertEquals(tag, service.fetchTag(forkId, OWNER, selection).destinationTagObject());
            assertEquals(tag, service.fetchTag(forkId, OWNER, selection).destinationTagObject());
            assertNull(target.getGitRepository().exactRef("refs/tags/omitted"));
            assertEquals(local, target.getHeadCommit(BRANCH));
            fixture.git.deleteCentralRepository(sourceId);
        }
        var verifier = new GitCommitSignatureService(SigningTestKeys.verifierProperties(directory.resolve("keys")));
        try (var fixture = new Fixture(database, verifier)) {
            var target = fixture.git.getCentralRepository(forkId);
            assertEquals(base, fixture.catalog.getRepository(forkId).getForkPointCommit());
            assertEquals(local, target.getHeadCommit(BRANCH));
            assertEquals(incoming, new GitUpstreamRef(sourceId, BRANCH).head(target));
            assertEquals("new upstream architecture", target.getDslAtCommit(incoming));
            assertEquals(SignatureStatus.VERIFIED, target.verifyCommit(incoming).status());
            assertEquals(tag, target.getGitRepository().exactRef("refs/tags/review/v1").getObjectId().name());
            assertEquals(Constants.OBJ_TAG, target.getGitRepository().open(ObjectId.fromString(tag)).getType());
            assertTrue(target.isAncestor(base, incoming));
            assertFalse(target.isAncestor(local, incoming));
        }
    }

    @Test
    void concurrentFetchesWithMatchingBranchAndTagNamesStayInsideTheirOwnForks() throws Exception {
        String database = "jdbc:hsqldb:file:" + directory.resolve("overlap") + ";shutdown=true";
        try (var fixture = new Fixture(database, GitCommitSignatureService.disabled());
             var workers = Executors.newFixedThreadPool(2)) {
            var first = fixture.createSource(RepositoryVisibility.PRIVATE);
            var second = fixture.catalog.createCentralRepository("Second", "second-origin", null,
                    RepositoryVisibility.PRIVATE, OWNER, BRANCH);
            fixture.catalog.markProvisioningReady(second.getRepositoryId());
            var service = new CentralForkUpstreamService(fixture.catalog, fixture.memberships, fixture.git);
            record Pair(String forkId, String base, String incoming) {}
            var pairs = new java.util.ArrayList<Pair>();
            for (var origin : List.of(first, second)) {
                String sourceId = origin.getRepositoryId();
                var source = fixture.git.getCentralRepository(sourceId);
                String base = source.commitDsl(BRANCH, "base " + sourceId, OWNER, "Initial");
                var fork = fixture.service.createFork(sourceId, BRANCH, "Fork", "fork-" + origin.getSlug(),
                        null, RepositoryVisibility.PRIVATE, OWNER);
                String incoming = source.commitDsl(BRANCH, "next " + sourceId, OWNER, "New upstream");
                try (var walk = new RevWalk(source.getGitRepository())) {
                    Git.wrap(source.getGitRepository()).tag().setName("same-name").setAnnotated(false)
                            .setObjectId(walk.parseCommit(ObjectId.fromString(incoming))).call();
                }
                pairs.add(new Pair(fork.getRepositoryId(), base, incoming));
            }
            var start = new CountDownLatch(1);
            var tasks = pairs.stream().map(pair -> workers.submit(() -> {
                start.await();
                service.fetch(pair.forkId(), OWNER, new WorkspaceUpstreamService.Request(pair.incoming(), pair.base()));
                return service.fetchTag(pair.forkId(), OWNER,
                        new WorkspaceUpstreamService.TagRequest("same-name", pair.incoming(), pair.incoming()));
            })).toList();
            start.countDown();
            for (int i = 0; i < pairs.size(); i++) {
                var pair = pairs.get(i);
                assertEquals(pair.incoming(), tasks.get(i).get(30, TimeUnit.SECONDS).destinationTagObject());
                var fork = fixture.git.getCentralRepository(pair.forkId());
                assertEquals(pair.base(), fork.getHeadCommit(BRANCH));
                assertEquals(pair.incoming(), fork.getGitRepository().exactRef("refs/tags/same-name").getObjectId().name());
                assertFalse(fork.getGitRepository().getObjectDatabase().has(ObjectId.fromString(pairs.get(1 - i).incoming())));
            }
        }
    }

    @Test
    void bookkeepingFailureRetainsThePublishedForkPoint() throws Exception {
        String database = "jdbc:hsqldb:file:" + directory.resolve("failure") + ";shutdown=true";
        try (var fixture = new Fixture(database, GitCommitSignatureService.disabled())) {
            var source = fixture.createSource(RepositoryVisibility.PRIVATE);
            String sourceId = source.getRepositoryId();
            String branch = source.getDefaultBranch();
            var sourceGit = fixture.git.getCentralRepository(sourceId);
            String captured = sourceGit.commitDsl(branch, "source architecture", OWNER, "Selected source");
            var failingCatalog = mock(SystemRepositoryService.class, delegatesTo(fixture.catalog));
            doThrow(new IllegalStateException("Catalog completion failed")).when(failingCatalog).markProvisioningReady(anyString());
            var service = new ArchitectureRepositoryProvisioningService(failingCatalog, fixture.memberships, fixture.git, namespace -> "meta { language: \"taxdsl\"; }\n");

            assertThrows(IllegalStateException.class,
                    () -> service.createFork(sourceId, branch, "Fork", "central-fork", null, null, OWNER));

            var failed = fixture.rows.findBySlug("central-fork").orElseThrow();
            assertEquals(RepositoryLifecycleState.FAILED, failed.getLifecycleState());
            assertEquals(captured, failed.getForkPointCommit());
            assertEquals(captured, fixture.git.getCentralRepository(failed.getRepositoryId()).getHeadCommit(branch));
            assertEquals(captured, sourceGit.getHeadCommit(branch));
        }
    }

    static final class Fixture implements AutoCloseable {
        final EditorPersistenceFixture persistence;
        final SystemRepositoryRepository rows;
        final SystemRepositoryService catalog;
        final RepositoryMembershipService memberships;
        final DslGitRepositoryFactory git;
        final ArchitectureRepositoryProvisioningService service;

        Fixture(String database, GitCommitSignatureService signatures) {
            persistence = new EditorPersistenceFixture(database, SystemRepository.class, RepositoryMembership.class);
            var transactionManager = new JpaTransactionManager(persistence.factory);
            var entities = SharedEntityManagerCreator.createSharedEntityManager(persistence.factory);
            var repositories = new JpaRepositoryFactory(entities);
            var transactions = new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource());
            rows = repositories.getRepository(SystemRepositoryRepository.class);
            catalog = transactional(new SystemRepositoryService(rows), SystemRepositoryService.class, transactions);
            memberships = transactional(new RepositoryMembershipService(repositories.getRepository(RepositoryMembershipRepository.class)),
                    RepositoryMembershipService.class, transactions);
            git = new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(persistence.factory), catalog, signatures);
            service = new ArchitectureRepositoryProvisioningService(catalog, memberships, git, namespace -> "meta { language: \"taxdsl\"; }\n");
        }

        private static <T> T transactional(T target, Class<T> type, TransactionInterceptor transactions) {
            var proxy = new ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(transactions);
            return type.cast(proxy.getProxy());
        }

        SystemRepository createSource(RepositoryVisibility visibility) {
            var source = catalog.createCentralRepository("Source", "original-fork-source", null, visibility, OWNER, "main");
            return catalog.markProvisioningReady(source.getRepositoryId());
        }

        @Override public void close() throws Exception {
            try (persistence; git) {
                // Close Git handles before Hibernate.
            }
        }
    }
}
