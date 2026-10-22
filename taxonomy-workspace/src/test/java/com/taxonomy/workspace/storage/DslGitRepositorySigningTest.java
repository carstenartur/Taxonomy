package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.signing.GitCommitSignatureService;
import com.taxonomy.workspace.signing.SigningTestKeys;
import com.taxonomy.editor.EditorPersistenceFixture;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.GpgSignature;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static com.taxonomy.workspace.signing.SignatureStatus.VERIFIED;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DslGitRepositorySigningTest {
    @TempDir Path keys;

    @Test
    void bothLogicalRepositoryKindsSignOrdinaryAndExpectedHeadCommits() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        try (var factory = new DslGitRepositoryFactory(null, null, signatures)) {
            var workspace = factory.openWorkspaceRepository("alice");
            var central = factory.getCentralRepository("project");
            String first = workspace.commitDsl("draft", "# one", "alice", "Initial");
            String next = workspace.commitDslIfHeadMatches("draft", first, "# two", "alice", "Edit");
            String published = central.commitDsl("main", "# two", "alice", "Publish");
            assertThat(workspace.verifyCommit(first).status()).isEqualTo(VERIFIED);
            assertThat(workspace.verifyCommit(next).status()).isEqualTo(VERIFIED);
            assertThat(central.verifyCommit(published).status()).isEqualTo(VERIFIED);
            assertThat(workspace.getHeadCommitInfo("draft").signature().status()).isEqualTo(VERIFIED);
            assertThat(central.getHeadCommitInfo("main").signature().status()).isEqualTo(VERIFIED);
            assertThat(workspace.getDslHistory("draft")).allSatisfy(commit ->
                    assertThat(commit.signature().status()).isEqualTo(VERIFIED));
        }
    }

    @Test
    void hibernateStoragePreservesSignedObjectsInBothRepositoryKindsAfterReopening() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        String scope = UUID.randomUUID().toString();
        try (var persistence = new EditorPersistenceFixture("jdbc:hsqldb:mem:signatures-" + scope)) {
            var storage = new DefaultHibernateRepositoryFactory(persistence.factory);
            String workspaceHead;
            String centralHead;
            byte[] original;
            try (var writer = new DslGitRepositoryFactory(storage, null, signatures)) {
                var workspace = writer.openWorkspaceRepository(scope);
                workspaceHead = workspace.commitDsl("draft", "# personal", "alice", "Personal change");
                var evidence = CommitProvenance.capture("publish", workspace, "workspace:" + scope,
                        "draft", workspaceHead, null);
                centralHead = writer.getCentralRepository(scope).commitDsl("main", "# personal", "alice", "Publish", evidence);
                original = workspace.getGitRepository().open(ObjectId.fromString(workspaceHead), Constants.OBJ_COMMIT).getBytes();
            }
            try (var reopened = new DslGitRepositoryFactory(storage, null, signatures)) {
                var workspace = reopened.openWorkspaceRepository(scope);
                var central = reopened.getCentralRepository(scope);
                assertThat(workspace.getHeadCommit("draft")).isEqualTo(workspaceHead);
                assertThat(central.getHeadCommit("main")).isEqualTo(centralHead);
                assertThat(workspace.verifyCommit(workspaceHead).status()).isEqualTo(VERIFIED);
                assertThat(central.verifyCommit(centralHead).status()).isEqualTo(VERIFIED);
                central.verifySnapshot(centralHead);
                assertThat(central.getProvenance(centralHead).sources().getFirst().commitId()).isEqualTo(workspaceHead);
                assertThat(workspace.getGitRepository().open(ObjectId.fromString(workspaceHead), Constants.OBJ_COMMIT).getBytes())
                        .isEqualTo(original);
            }
        }
    }

    @Test
    void signingFailureLeavesBothExistingAndNewBranchesUnchanged() throws Exception {
        try (var repository = new DslGitRepository()) {
            String accepted = repository.commitDsl("draft", "# old", "alice", "Initial");
            var unavailableSigner = mock(GitCommitSignatureService.class);
            doThrow(new IOException("Signing unavailable")).when(unavailableSigner)
                    .sign(any(CommitBuilder.class), anyString(), anyString());
            repository.withSignatureService(unavailableSigner, "workspace-alice");
            assertThatThrownBy(() -> repository.commitDslIfHeadMatches(
                    "draft", accepted, "# changed", "alice", "Change")).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> repository.commitDsl("new", "# changed", "alice", "New"))
                    .isInstanceOf(IOException.class);
            assertThat(repository.getHeadCommit("draft")).isEqualTo(accepted);
            assertThat(repository.getHeadCommit("new")).isNull();
        }
    }

    @Test
    void incomingVerificationChecksIntermediateCommitsBeforeFastForward() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        try (var repository = new DslGitRepository().withSignatureService(signatures, "central")) {
            String accepted = repository.commitDsl("main", "# base", "alice", "Base");
            String malformed;
            try (var walk = new RevWalk(repository.getGitRepository());
                 var inserter = repository.getGitRepository().newObjectInserter()) {
                var prior = walk.parseCommit(ObjectId.fromString(accepted));
                var builder = new CommitBuilder();
                builder.setTreeId(prior.getTree()); builder.setParentId(prior);
                builder.setAuthor(prior.getAuthorIdent()); builder.setCommitter(prior.getCommitterIdent());
                builder.setMessage("Tampered intermediate");
                builder.setGpgSignature(new GpgSignature("invalid".getBytes(StandardCharsets.US_ASCII)));
                malformed = inserter.insert(builder).name(); inserter.flush();
            }
            repository.createBranchAtCommit("incoming", malformed);
            repository.commitDsl("incoming", "# signed tip", "bob", "Signed tip cannot hide invalid ancestry");
            assertThatThrownBy(() -> repository.merge("incoming", "main")).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> repository.restore(malformed, "main")).isInstanceOf(IOException.class);
            assertThat(repository.getHeadCommit("main")).isEqualTo(accepted);
        }
    }

    @Test
    void directMergeRetainsTransferProofsInSignedParentsWithoutMergingTheirMetadata() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        try (var repository = new DslGitRepository().withSignatureService(signatures, "central");
             var source = new DslGitRepository().withSignatureService(signatures, "workspace")) {
            String baseDsl = "# first\n# middle one\n# middle two\n# middle three\n# last\n";
            String oursDsl = baseDsl.replace("# first", "# changed first");
            String theirsDsl = baseDsl.replace("# last", "# changed last");
            String base = repository.commitDsl("main", baseDsl, "alice", "Base");
            repository.createBranchAtCommit("incoming", base);
            String firstSource = source.commitDsl("first", oursDsl, "alice", "First source");
            String lastSource = source.commitDsl("last", theirsDsl, "bob", "Last source");
            var firstProof = CommitProvenance.capture("publish", source, "workspace", "first", firstSource, null);
            var lastProof = CommitProvenance.capture("publish", source, "workspace", "last", lastSource, null);
            String ours = repository.commitDsl("main", oursDsl, "alice", "Publish first", firstProof);
            String theirs = repository.commitDsl("incoming", theirsDsl, "bob", "Publish last", lastProof);

            String merged = repository.merge("incoming", "main");

            assertThat(merged).isNotNull();
            assertThat(repository.verifyCommit(merged).status()).isEqualTo(VERIFIED);
            assertThat(repository.getDslAtCommit(merged)).contains("# changed first", "# changed last");
            assertThat(repository.getProvenance(merged)).isNull();
            try (var walk = new RevWalk(repository.getGitRepository())) {
                assertThat(walk.parseCommit(ObjectId.fromString(merged)).getParents())
                        .extracting(parent -> parent.name()).containsExactly(ours, theirs);
            }
            assertThat(repository.getProvenance(ours).sources().getFirst().commitId()).isEqualTo(firstSource);
            assertThat(repository.getProvenance(theirs).sources().getFirst().commitId()).isEqualTo(lastSource);
            repository.verifySnapshot(ours);
            repository.verifySnapshot(theirs);
        }
    }

    @Test
    void cherryPickAndRevertCreateFreshVerifiableObjects() throws Exception {
        try (var repository = new DslGitRepository().withSignatureService(SigningTestKeys.createService(keys), "workspace")) {
            String initial = repository.commitDsl("main", "# one", "alice", "Initial");
            repository.createBranchAtCommit("feature", initial);
            String edit = repository.commitDsl("feature", "# two", "alice", "Edit");
            String picked = repository.cherryPick(edit, "main");
            assertThat(picked).isNotNull();
            assertThat(repository.verifyCommit(picked).status()).isEqualTo(VERIFIED);
            String reverted = repository.revert(picked, "main");
            assertThat(repository.verifyCommit(reverted).status()).isEqualTo(VERIFIED);
            assertThat(repository.getDslAtCommit(reverted)).isEqualTo("# one");
            String restored = repository.restore(edit, "main");
            assertThat(repository.verifyCommit(restored).status()).isEqualTo(VERIFIED);
            assertThat(repository.getProvenance(restored).sources().getFirst().commitId()).isEqualTo(edit);
            assertThat(repository.getGitRepository().open(ObjectId.fromString(edit), Constants.OBJ_COMMIT).getBytes())
                    .isNotEqualTo(repository.getGitRepository().open(ObjectId.fromString(picked), Constants.OBJ_COMMIT).getBytes());
        }
    }
}
