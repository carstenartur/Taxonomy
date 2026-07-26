package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end JGit transport tests using a temporary local bare repository.
 * No external Git service or GitHub-specific orchestration is required.
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
    private ExternalGitSyncService service;
    private String remoteUri;

    @BeforeEach
    void setUp() throws Exception {
        Path bareDirectory = temporaryDirectory.resolve("remote.git");
        try (Git ignored = Git.init().setBare(true).setDirectory(bareDirectory.toFile()).call()) {
            // Repository creation is the setup action.
        }
        remoteUri = bareDirectory.toUri().toString();

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

        SystemRepositoryService repositoryService = mock(SystemRepositoryService.class);
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
    }

    @Test
    void remoteOnlyCommitFastForwardsLocalSharedBranch() throws Exception {
        String remoteDsl = "architecture {\n  description \"remote\"\n}\n";
        ObjectId remoteCommit = commitToRemote(remoteDsl, "remote change", "fast-forward-work");

        String result = service.fullSync("alice");

        assertEquals(remoteCommit.name(), result);
        assertEquals(remoteDsl, systemDslRepository.getDslAtHead(BRANCH));
        assertEquals(remoteCommit.name(), systemRepository.getLastFetchCommit());
    }

    @Test
    void remoteAncestorDoesNotOverwriteLocalOnlyCommit() throws Exception {
        String localDsl = "architecture {\n  description \"local only\"\n}\n";
        String localCommit = systemDslRepository.commitDsl(BRANCH, localDsl, "alice", "local change");

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
        String localCommit = systemDslRepository.commitDsl(BRANCH, localDsl, "alice", "local conflict");

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

    private ObjectId commitToRemote(String dsl, String message, String directoryName) throws Exception {
        Path workDirectory = temporaryDirectory.resolve(directoryName);
        try (Git git = Git.cloneRepository()
                .setURI(remoteUri)
                .setBranch("refs/heads/" + BRANCH)
                .setDirectory(workDirectory.toFile())
                .call()) {
            Files.writeString(workDirectory.resolve(DSL_FILE), dsl, StandardCharsets.UTF_8);
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
