package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.signing.GitCommitSignatureService;
import com.taxonomy.workspace.signing.SignatureStatus;
import com.taxonomy.workspace.signing.SigningTestKeys;
import com.taxonomy.editor.EditorPersistenceFixture;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.GpgSignature;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

    @ParameterizedTest
    @EnumSource(value = SignatureStatus.class, names = {"INVALID", "UNTRUSTED"})
    void revertRejectsUnacceptableParentEvenWhenTheRevertedChildIsTrusted(SignatureStatus parentStatus)
            throws Exception {
        var signatures = SigningTestKeys.createService(keys.resolve("trusted"));
        try (var repository = new DslGitRepository().withSignatureService(signatures, "central")) {
            String rejectedParent;
            if (parentStatus == SignatureStatus.UNTRUSTED) {
                repository.withSignatureService(SigningTestKeys.createIndependentService(
                        keys.resolve("untrusted"), "Other operator <other@example.test>"), "central");
                rejectedParent = repository.commitDsl("rejected", "# parent content", "bob", "Untrusted parent");
                repository.withSignatureService(signatures, "central");
            } else {
                String original = repository.commitDsl("rejected", "# parent content", "alice", "Original");
                String raw = new String(repository.getGitRepository()
                        .open(ObjectId.fromString(original), Constants.OBJ_COMMIT).getBytes(), StandardCharsets.UTF_8);
                String tampered = raw.replace("\n\nOriginal", "\n\nTampered parent");
                assertThat(tampered).isNotEqualTo(raw);
                try (var inserter = repository.getGitRepository().newObjectInserter()) {
                    rejectedParent = inserter.insert(Constants.OBJ_COMMIT,
                            tampered.getBytes(StandardCharsets.UTF_8)).name();
                    inserter.flush();
                }
            }
            repository.createBranchAtCommit("incoming", rejectedParent);
            String child = repository.commitDsl("incoming", "# child content", "alice", "Trusted child");
            String target = repository.commitDsl("main", "# child content", "alice", "Accepted target");
            assertThat(repository.verifyCommit(rejectedParent).status()).isEqualTo(parentStatus);
            assertThat(repository.verifyCommit(child).status()).isEqualTo(VERIFIED);

            assertThatThrownBy(() -> repository.revert(child, "main")).isInstanceOf(IOException.class);

            assertThat(repository.getHeadCommit("main")).isEqualTo(target);
            assertThat(repository.getDslAtHead("main")).isEqualTo("# child content");
        }
    }

    @Test
    void cherryPickMergesDslAndOtherFilesWithoutConflictingTransferProofs() throws Exception {
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
            String ours = repository.commitDsl("main", oursDsl, "alice", "First transfer",
                    CommitProvenance.capture("publish", source, "workspace", "first", firstSource, null));
            repository.commitDsl("incoming", theirsDsl, "bob", "Last transfer",
                    CommitProvenance.capture("publish", source, "workspace", "last", lastSource, null));
            String pickedSource = addNote(repository, "incoming");

            String picked = repository.cherryPick(pickedSource, "main");

            assertThat(picked).isNotNull();
            assertThat(repository.getDslAtCommit(picked)).contains("# changed first", "# changed last");
            assertNote(repository, picked);
            assertSingleParent(repository, picked, ours);
            assertThat(repository.verifyCommit(picked).status()).isEqualTo(VERIFIED);
            var proof = repository.getProvenance(picked);
            assertThat(proof.operation()).isEqualTo("cherry-pick");
            assertThat(proof.sources()).singleElement().satisfies(evidence -> {
                assertThat(evidence.repositoryId()).isEqualTo("central");
                assertThat(evidence.commitId()).isEqualTo(pickedSource);
            });
            repository.verifySnapshot(picked);
        }
    }

    @Test
    void revertMergesDslAndOtherFilesWithoutConflictingTransferProofs() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        try (var repository = new DslGitRepository().withSignatureService(signatures, "central");
             var source = new DslGitRepository().withSignatureService(signatures, "workspace")) {
            String baseDsl = "# first\n# middle one\n# middle two\n# middle three\n# last\n";
            String firstDsl = baseDsl.replace("# first", "# changed first");
            String bothDsl = firstDsl.replace("# last", "# changed last");
            String base = repository.commitDsl("main", baseDsl, "alice", "Base");
            String firstSource = source.commitDsl("first", firstDsl, "alice", "First source");
            String lastSource = source.commitDsl("last", bothDsl, "bob", "Last source");
            String revertedSource = repository.commitDsl("main", firstDsl, "alice", "First transfer",
                    CommitProvenance.capture("publish", source, "workspace", "first", firstSource, null));
            repository.commitDsl("main", bothDsl, "bob", "Last transfer",
                    CommitProvenance.capture("publish", source, "workspace", "last", lastSource, null));
            String target = addNote(repository, "main");

            String reverted = repository.revert(revertedSource, "main");

            assertThat(reverted).isNotNull();
            assertThat(repository.getDslAtCommit(reverted)).isEqualTo(baseDsl.replace("# last", "# changed last"));
            assertNote(repository, reverted);
            assertSingleParent(repository, reverted, target);
            assertThat(repository.verifyCommit(reverted).status()).isEqualTo(VERIFIED);
            var proof = repository.getProvenance(reverted);
            assertThat(proof.operation()).isEqualTo("revert");
            assertThat(proof.sources()).singleElement().satisfies(evidence -> {
                assertThat(evidence.repositoryId()).isEqualTo("central");
                assertThat(evidence.commitId()).isEqualTo(base);
                assertThat(evidence.baseCommitId()).isEqualTo(revertedSource);
            });
            repository.verifySnapshot(reverted);
        }
    }

    @Test
    void cherryPickAndRevertStillRejectRealDslConflicts() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        try (var repository = new DslGitRepository().withSignatureService(signatures, "central");
             var source = new DslGitRepository().withSignatureService(signatures, "workspace")) {
            String base = repository.commitDsl("main", "# base", "alice", "Base");
            repository.createBranchAtCommit("incoming", base);
            String firstSource = source.commitDsl("first", "# ours", "alice", "First source");
            String lastSource = source.commitDsl("last", "# theirs", "bob", "Last source");
            String ours = repository.commitDsl("main", "# ours", "alice", "First transfer",
                    CommitProvenance.capture("publish", source, "workspace", "first", firstSource, null));
            String theirs = repository.commitDsl("incoming", "# theirs", "bob", "Last transfer",
                    CommitProvenance.capture("publish", source, "workspace", "last", lastSource, null));

            assertThat(repository.cherryPick(theirs, "main")).isNull();
            assertThat(repository.getHeadCommit("main")).isEqualTo(ours);
            assertThat(repository.revert(ours, "incoming")).isNull();
            assertThat(repository.getHeadCommit("incoming")).isEqualTo(theirs);
        }
    }

    private static String addNote(DslGitRepository repository, String branch) throws IOException {
        var git = repository.getGitRepository();
        try (var walk = new RevWalk(git); var inserter = git.newObjectInserter(); var treeWalk = new TreeWalk(git)) {
            var parent = walk.parseCommit(ObjectId.fromString(repository.getHeadCommit(branch)));
            var tree = new TreeFormatter();
            treeWalk.addTree(parent.getTree());
            while (treeWalk.next()) {
                tree.append(treeWalk.getNameString(), treeWalk.getFileMode(0), treeWalk.getObjectId(0));
            }
            tree.append("notes.txt", FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, "Keep this file".getBytes(StandardCharsets.UTF_8)));
            var commit = new CommitBuilder();
            commit.setTreeId(inserter.insert(tree));
            commit.setParentId(parent);
            commit.setAuthor(parent.getAuthorIdent());
            commit.setCommitter(parent.getCommitterIdent());
            commit.setMessage("Add unrelated file");
            repository.signCommit(commit, branch);
            var commitId = inserter.insert(commit);
            inserter.flush();
            var ref = git.updateRef(Constants.R_HEADS + branch);
            ref.setExpectedOldObjectId(parent);
            ref.setNewObjectId(commitId);
            assertThat(ref.update()).isEqualTo(RefUpdate.Result.FAST_FORWARD);
            return commitId.name();
        }
    }

    private static void assertNote(DslGitRepository repository, String commit) throws IOException {
        try (var walk = new RevWalk(repository.getGitRepository())) {
            var head = walk.parseCommit(ObjectId.fromString(commit));
            try (var note = TreeWalk.forPath(repository.getGitRepository(), "notes.txt", head.getTree())) {
                assertThat(note).isNotNull();
                assertThat(repository.getGitRepository().open(note.getObjectId(0)).getBytes())
                        .isEqualTo("Keep this file".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void assertSingleParent(DslGitRepository repository, String commit, String expectedParent)
            throws IOException {
        try (var walk = new RevWalk(repository.getGitRepository())) {
            assertThat(walk.parseCommit(ObjectId.fromString(commit)).getParents())
                    .extracting(parent -> parent.name()).containsExactly(expectedParent);
        }
    }
}
