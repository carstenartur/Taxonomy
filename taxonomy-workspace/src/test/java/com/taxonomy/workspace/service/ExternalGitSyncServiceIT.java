package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.Daemon;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.resolver.FileResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end JGit transport tests using a temporary bare repository served by
 * an in-process daemon bound exclusively to the loopback interface.
 *
 * <p>The application repository is a DFS repository and intentionally has no
 * local filesystem abstraction. A {@code file://} remote would therefore test
 * an unsupported TransportLocal assumption rather than real external Git
 * synchronization. The loopback daemon exercises upload-pack/receive-pack
 * through the same transport boundary as a remote Git service without any
 * external infrastructure or GitHub-specific orchestration.</p>
 */
class ExternalGitSyncServiceIT {

    private static final String BRANCH = "draft";
    private static final String DSL_FILE = "architecture.taxdsl";
    private static final String BASE_DSL = "architecture {\n  description \"base\"\n}\n";

    @TempDir
    Path temporaryDirectory;

    private DslGitRepositoryFactory repositoryFactory;
    private DslGitRepository systemDslRepository;
    private SystemRepository systemRepository;
    private SystemRepositoryService repositoryService;
    private ExternalGitSyncService service;
    private Daemon gitDaemon;
    private String remoteUri;

    @BeforeEach
    void setUp() throws Exception {
        Path bareDirectory = temporaryDirectory.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setDirectory(bareDirectory.toFile())
                .call()) {
            // Repository creation is the setup action.
        }

        gitDaemon = new Daemon(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0));
        gitDaemon.setRepositoryResolver(
                new FileResolver<>(temporaryDirectory.toFile(), true));
        gitDaemon.getService("git-upload-pack").setEnabled(true);
        gitDaemon.getService("git-receive-pack").setEnabled(true);
        gitDaemon.start();
        remoteUri = "git://127.0.0.1:"
                + gitDaemon.getAddress().getPort()
                + "/remote.git";

        repositoryFactory = new DslGitRepositoryFactory(null);
        systemDslRepository = repositoryFactory.getSystemRepository();
        systemDslRepository.commitDsl(BRANCH, BASE_DSL, "alice", "base");

        systemRepository = new SystemRepository();
        systemRepository.setRepositoryId(UUID.randomUUID().toString());
        systemRepository.setDisplayName("External sync test repository");
        systemRepository.setTopologyMode(RepositoryTopologyMode.EXTERNAL_CANONICAL);
        systemRepository.setDefaultBranch(BRANCH);
        systemRepository.setExternalUrl(remoteUri);
        systemRepository.setPrimaryRepo(true);
        systemRepository.setCreatedAt(Instant.now());

        repositoryService = mock(SystemRepositoryService.class);
        when(repositoryService.getPrimaryRepository()).thenReturn(systemRepository);
        when(repositoryService.getSharedBranch()).thenReturn(BRANCH);

        service = new ExternalGitSyncService(repositoryFactory, repositoryService);
        service.pushToExternal(BRANCH);
    }

    @AfterEach
    void tearDown() {
        if (repositoryFactory != null) {
            repositoryFactory.close();
        }
        if (gitDaemon != null) {
            gitDaemon.stop();
        }
    }

    @Test
    void remoteOnlyCommitFastForwardsLocalSharedBranch() throws Exception {
        String remoteDsl = "architecture {\n  description \"remote\"\n}\n";
        ObjectId remoteCommit = commitToRemote(
                remoteDsl, "remote change", "fast-forward-work");

        String result = service.fullSync("alice");

        assertEquals(remoteCommit.name(), result);
        assertEquals(remoteDsl, systemDslRepository.getDslAtHead(BRANCH));
        assertEquals(remoteCommit.name(), systemRepository.getLastFetchCommit());
    }

    @Test
    void remoteAncestorDoesNotOverwriteLocalOnlyCommit() throws Exception {
        String localDsl = "architecture {\n  description \"local only\"\n}\n";
        String localCommit = systemDslRepository.commitDsl(
                BRANCH, localDsl, "alice", "local change");

        String result = service.fullSync("alice");

        assertEquals(localCommit, result);
        assertEquals(localDsl, systemDslRepository.getDslAtHead(BRANCH));
    }

    @Test
    void divergentConflictingChangesLeaveLocalBranchUnchanged() throws Exception {
        commitToRemote(
                "architecture {\n  description \"remote conflict\"\n}\n",
                "remote conflict",
                "conflict-work");
        String localDsl = "architecture {\n  description \"local conflict\"\n}\n";
        String localCommit = systemDslRepository.commitDsl(
                BRANCH, localDsl, "alice", "local conflict");

        assertThrows(ExternalGitSyncService.ExternalSyncConflictException.class,
                () -> service.fullSync("alice"));

        assertEquals(localCommit, systemDslRepository.getHeadCommit(BRANCH));
        assertEquals(localDsl, systemDslRepository.getDslAtHead(BRANCH));
    }

    @Test
    void rejectedNonFastForwardPushDoesNotAdvanceLastPushTimestamp() throws Exception {
        Instant successfulSeedPush = systemRepository.getLastPushAt();
        assertNotNull(successfulSeedPush);

        commitToRemote(
                "architecture {\n  description \"remote advanced\"\n}\n",
                "remote advanced",
                "rejected-push-work");
        systemDslRepository.commitDsl(
                BRANCH,
                "architecture {\n  description \"local advanced\"\n}\n",
                "alice",
                "local advanced");

        assertThrows(ExternalGitSyncService.ExternalPushRejectedException.class,
                () -> service.pushToExternal(BRANCH));
        assertEquals(successfulSeedPush, systemRepository.getLastPushAt());
    }

    @Test
    void fetchTracksTheConfiguredSharedBranchCommit() throws Exception {
        ObjectId remoteCommit = commitToRemote(
                "architecture {\n  description \"tracked remote\"\n}\n",
                "tracked remote",
                "fetch-work");

        service.fetchFromExternal();

        assertEquals(remoteCommit.name(), systemRepository.getLastFetchCommit());
    }

    @Test
    void fullSyncReturnsNullWithoutChangingLocalRefsWhenRemoteBranchIsAbsent() throws Exception {
        when(repositoryService.getSharedBranch()).thenReturn("absent");

        assertNull(service.fullSync("alice"));
        assertNull(systemDslRepository.getHeadCommit("absent"));
    }

    @Test
    void fullSyncRecreatesMissingLocalBranchAtFetchedRemoteCommit() throws Exception {
        String remoteCommit = systemDslRepository.getHeadCommit(BRANCH);
        RefUpdate delete = systemDslRepository.getGitRepository()
                .updateRef(Constants.R_HEADS + BRANCH);
        delete.setExpectedOldObjectId(ObjectId.fromString(remoteCommit));
        delete.setForceUpdate(true);
        RefUpdate.Result deletion = delete.delete();
        assertTrue(deletion == RefUpdate.Result.FORCED
                || deletion == RefUpdate.Result.NO_CHANGE);
        assertNull(systemDslRepository.getHeadCommit(BRANCH));

        String result = service.fullSync(null);

        assertEquals(remoteCommit, result);
        assertEquals(remoteCommit, systemDslRepository.getHeadCommit(BRANCH));
    }

    @Test
    void selectedRepositorySynchronizesWithoutChangingPrimaryOrItsMetadata() throws Exception {
        String primaryHead = systemDslRepository.getHeadCommit(BRANCH);
        Instant primaryPush = systemRepository.getLastPushAt();
        Path secondBare = temporaryDirectory.resolve("second.git");
        try (Git ignored = Git.init().setBare(true).setDirectory(secondBare.toFile()).call()) {
            // A separate remote proves both source and destination routing.
        }
        SystemRepository selected = new SystemRepository();
        selected.setRepositoryId("selected");
        selected.setPrimaryRepo(false);
        selected.setTopologyMode(RepositoryTopologyMode.EXTERNAL_CANONICAL);
        selected.setDefaultBranch("review");
        selected.setExternalUrl(remoteUri.replace("remote.git", "second.git"));
        when(repositoryService.getRepository("selected")).thenReturn(selected);
        DslGitRepository second = repositoryFactory.getCentralRepository("selected");
        String selectedHead = second.commitDsl("review", "architecture { description \"second\" }", "bob", "second base");

        service.pushToExternal("selected", "review");
        try (Git remote = Git.open(secondBare.toFile())) {
            assertEquals(selectedHead, remote.getRepository().resolve("refs/heads/review").name());
            assertNull(remote.getRepository().resolve("refs/heads/" + BRANCH));
        }
        try (Git git = Git.cloneRepository().setURI(selected.getExternalUrl())
                .setBranch("refs/heads/review").setDirectory(temporaryDirectory.resolve("second-work").toFile()).call()) {
            Files.writeString(git.getRepository().getWorkTree().toPath().resolve(DSL_FILE), "architecture { description \"remote second\" }");
            git.add().addFilepattern(DSL_FILE).call();
            String remoteHead = git.commit().setMessage("remote second")
                    .setAuthor("remote", "remote@example.test").call().name();
            git.push().add("review").call();
            assertEquals(remoteHead, service.fullSync("selected", "bob"));
            assertEquals(remoteHead, second.getHeadCommit("review"));
        }
        assertEquals(primaryHead, systemDslRepository.getHeadCommit(BRANCH));
        assertEquals(primaryPush, systemRepository.getLastPushAt());
        assertNull(systemRepository.getLastFetchAt());
        assertNull(second.getHeadCommit(BRANCH));
        assertNotNull(selected.getLastFetchAt());
    }

    @Test
    void invalidIncomingSignatureCannotChangeTheActiveSharedBranch() throws Exception {
        String acceptedHead = systemDslRepository.getHeadCommit(BRANCH);
        try (Git git = Git.cloneRepository().setURI(remoteUri).setBranch("refs/heads/" + BRANCH)
                .setDirectory(temporaryDirectory.resolve("invalid-signature").toFile()).call()) {
            var repository = git.getRepository();
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(repository);
                 var inserter = repository.newObjectInserter()) {
                var builder = new org.eclipse.jgit.lib.CommitBuilder();
                builder.setParentId(ObjectId.fromString(acceptedHead));
                builder.setTreeId(walk.parseCommit(ObjectId.fromString(acceptedHead)).getTree());
                builder.setAuthor(new org.eclipse.jgit.lib.PersonIdent("untrusted", "untrusted@example.test"));
                builder.setCommitter(builder.getAuthor());
                builder.setMessage("invalid external signature");
                builder.setGpgSignature(new org.eclipse.jgit.lib.GpgSignature(
                        "-----BEGIN PGP SIGNATURE-----\ninvalid\n-----END PGP SIGNATURE-----".getBytes(StandardCharsets.US_ASCII)));
                ObjectId invalidHead = inserter.insert(builder);
                inserter.flush();
                RefUpdate update = repository.updateRef("refs/heads/" + BRANCH);
                update.setNewObjectId(invalidHead);
                assertEquals(RefUpdate.Result.FAST_FORWARD, update.update());
                git.push().add(BRANCH).call();
            }
        }
        systemDslRepository.withSignatureService(
                new com.taxonomy.workspace.signing.GitCommitSignatureService(
                        new com.taxonomy.workspace.signing.GitSigningProperties()), "external-test");

        assertThrows(java.io.IOException.class, () -> service.fullSync("alice"));

        assertEquals(acceptedHead, systemDslRepository.getHeadCommit(BRANCH));
        assertNull(systemDslRepository.getHeadCommit("__external_sync__/" + BRANCH));
        assertNull(systemRepository.getLastFetchAt());
    }

    @Test
    void validSignedIncomingCommitPreservesExactObjectAndSignatureOverTransport() throws Exception {
        String acceptedUnsignedBase = systemDslRepository.getHeadCommit(BRANCH);
        var signer = com.taxonomy.workspace.signing.SigningTestKeys.createService(
                temporaryDirectory.resolve("external-signing"));
        byte[] originalRawCommit;
        byte[] originalSignature;
        ObjectId signedHead;
        try (Git git = Git.cloneRepository().setURI(remoteUri).setBranch("refs/heads/" + BRANCH)
                .setDirectory(temporaryDirectory.resolve("valid-signature").toFile()).call()) {
            var repository = git.getRepository();
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(repository);
                 var inserter = repository.newObjectInserter()) {
                var builder = new org.eclipse.jgit.lib.CommitBuilder();
                builder.setParentId(ObjectId.fromString(acceptedUnsignedBase));
                builder.setTreeId(walk.parseCommit(ObjectId.fromString(acceptedUnsignedBase)).getTree());
                builder.setAuthor(new org.eclipse.jgit.lib.PersonIdent("external operator", "operator@example.test"));
                builder.setCommitter(builder.getAuthor());
                builder.setMessage("signed external model commit");
                signer.sign(builder, "external-operator", BRANCH);
                signedHead = inserter.insert(builder);
                inserter.flush();
                originalRawCommit = repository.open(signedHead, Constants.OBJ_COMMIT).getBytes();
                originalSignature = org.eclipse.jgit.revwalk.RevCommit.parse(originalRawCommit).getRawGpgSignature();
                assertNotNull(originalSignature);
                assertTrue(new String(originalSignature, StandardCharsets.US_ASCII).contains("-----END PGP SIGNATURE-----"));
                RefUpdate update = repository.updateRef("refs/heads/" + BRANCH);
                update.setExpectedOldObjectId(ObjectId.fromString(acceptedUnsignedBase));
                update.setNewObjectId(signedHead);
                assertEquals(RefUpdate.Result.FAST_FORWARD, update.update());
            }
            for (var result : git.push().add(BRANCH).call()) {
                for (var update : result.getRemoteUpdates()) {
                    assertEquals(RemoteRefUpdate.Status.OK, update.getStatus());
                }
            }
        }
        var verificationProperties = com.taxonomy.workspace.signing.SigningTestKeys.verifierProperties(
                temporaryDirectory.resolve("incoming-trust"));
        verificationProperties.setRequireSignatures(true);
        systemDslRepository.withSignatureService(
                new com.taxonomy.workspace.signing.GitCommitSignatureService(verificationProperties), "external-test");

        String integrated = service.fullSync("alice");

        assertEquals(signedHead.name(), integrated);
        byte[] transported = systemDslRepository.getGitRepository().open(signedHead, Constants.OBJ_COMMIT).getBytes();
        org.junit.jupiter.api.Assertions.assertArrayEquals(originalRawCommit, transported);
        try (var walk = new org.eclipse.jgit.revwalk.RevWalk(systemDslRepository.getGitRepository())) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(originalSignature,
                    walk.parseCommit(signedHead).getRawGpgSignature());
        }
        assertEquals(com.taxonomy.workspace.signing.SignatureStatus.VERIFIED,
                systemDslRepository.verifyCommit(integrated).status());
        assertEquals(com.taxonomy.workspace.signing.SignatureStatus.UNSIGNED,
                systemDslRepository.verifyCommit(acceptedUnsignedBase).status());
        assertEquals(integrated, systemRepository.getLastFetchCommit());
    }

    @Test
    void fullSyncPreservesAnExistingBranchWithTheHistoricalStagingName() throws Exception {
        String existingBranch = "__external_sync__/" + BRANCH;
        systemDslRepository.createBranch(existingBranch, BRANCH);
        String existingHead = systemDslRepository.commitDsl(existingBranch,
                "architecture { description \"user staging branch\" }", "alice", "user work");
        ObjectId remoteCommit = commitToRemote(
                "architecture { description \"incoming change\" }", "incoming", "existing-staging");

        assertEquals(remoteCommit.name(), service.fullSync("alice"));

        assertEquals(existingHead, systemDslRepository.getHeadCommit(existingBranch));
        assertEquals(java.util.Set.of(BRANCH, existingBranch),
                java.util.Set.copyOf(systemDslRepository.getBranchNames()));
    }

    private ObjectId commitToRemote(String dsl,
                                    String message,
                                    String directoryName) throws Exception {
        Path workDirectory = temporaryDirectory.resolve(directoryName);
        try (Git git = Git.cloneRepository()
                .setURI(remoteUri)
                .setBranch("refs/heads/" + BRANCH)
                .setDirectory(workDirectory.toFile())
                .call()) {
            Files.writeString(
                    workDirectory.resolve(DSL_FILE),
                    dsl,
                    StandardCharsets.UTF_8);
            git.add().addFilepattern(DSL_FILE).call();
            ObjectId commitId = git.commit()
                    .setMessage(message)
                    .setAuthor("remote", "remote@example.test")
                    .setCommitter("remote", "remote@example.test")
                    .call()
                    .getId();

            var pushResults = git.push().setRemote("origin").add(BRANCH).call();
            for (var pushResult : pushResults) {
                for (RemoteRefUpdate update : pushResult.getRemoteUpdates()) {
                    assertEquals(RemoteRefUpdate.Status.OK, update.getStatus());
                }
            }
            return commitId;
        }
    }
}
