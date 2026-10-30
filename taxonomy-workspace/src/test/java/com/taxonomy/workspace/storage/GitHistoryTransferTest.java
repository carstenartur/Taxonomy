package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.signing.SignatureStatus;
import com.taxonomy.workspace.signing.SigningTestKeys;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class GitHistoryTransferTest {
    @TempDir Path keys;

    @Test void transferredHistorySurvivesClosingAndReopeningHibernateStorage() throws Exception {
        String database = "jdbc:hsqldb:mem:history-transfer-" + java.util.UUID.randomUUID();
        String first, published;
        try (var persistence = new com.taxonomy.editor.EditorPersistenceFixture(database)) {
            var source = persistence.repositories.openWorkspaceRepository("author");
            var target = persistence.repositories.getCentralRepository("architecture");
            first = source.commitDsl("main", "# requirement 9", "architect", "Initial requirement contribution");
            String tenth = source.commitDsl("main", "# requirement 10", "architect", "Next requirement contribution");
            String accepted = target.commitDsl("main", "# central", "owner", "Independent architecture");
            GitHistoryTransfer.copyCommitClosure(source, target, tenth, List.of(accepted));
            published = target.commitMergeDslIfHeadMatches("main", accepted, tenth, "# merged", "architect", "Publish both contributions", null);
        }
        try (var reopened = new com.taxonomy.editor.EditorPersistenceFixture(database)) {
            var target = reopened.repositories.getCentralRepository("architecture");
            assertThat(target.getHeadCommit("main")).isEqualTo(published);
            assertThat(target.isAncestor(first, published)).isTrue();
            assertThat(target.getDslAtCommit(first)).isEqualTo("# requirement 9");
            assertThat(target.getDslHistory("main")).extracting(DslCommit::message).contains("Initial requirement contribution");
        }
    }

    @Test void exactOriginalCommitsAndTheirSignaturesRemainParentsOfThePublication() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        try (var source = new DslGitRepository().withSignatureService(signatures, "workspace");
             var target = new DslGitRepository().withSignatureService(signatures, "central")) {
            String first = source.commitDsl("draft", "# requirement 9", "alice", "Review ninth requirement");
            String tenth = source.commitDsl("draft", "# requirement 10", "alice", "Implement tenth requirement");
            String central = target.commitDsl("main", "# central", "bob", "Central architecture");
            byte[] original = source.getGitRepository().open(ObjectId.fromString(first)).getBytes();
            GitHistoryTransfer.copyCommitClosure(source, target, tenth, List.of(central));
            assertThat(target.getHeadCommit("main")).isEqualTo(central);
            assertThat(target.getGitRepository().open(ObjectId.fromString(first)).getBytes()).isEqualTo(original);
            String published = target.commitMergeDslIfHeadMatches("main", central, tenth, "# merged", "alice", "Publish contributions", null);
            try (var walk = new RevWalk(target.getGitRepository())) {
                var commit = walk.parseCommit(ObjectId.fromString(published));
                assertThat(commit.getParents()).extracting(ObjectId::name).containsExactly(central, tenth);
                assertThat(walk.isMergedInto(walk.parseCommit(ObjectId.fromString(first)), commit)).isTrue();
            }
            assertThat(target.verifyCommit(first).status()).isEqualTo(SignatureStatus.VERIFIED);
            assertThat(target.verifyCommit(published).status()).isEqualTo(SignatureStatus.VERIFIED);
            assertThat(target.getDslAtCommit(first)).isEqualTo("# requirement 9");
            assertThatThrownBy(() -> target.commitMergeDslIfHeadMatches("main", central, tenth, "# stale", "alice", "Stale", null))
                    .isInstanceOf(BranchHeadConflictException.class);
            assertThat(target.getHeadCommit("main")).isEqualTo(published);
            GitHistoryTransfer.copyCommitClosure(target, source, published, List.of(tenth));
            assertThat(source.fastForwardIfHeadMatches("draft", tenth, published, "alice", "Adopt published architecture"))
                    .isEqualTo(published);
            assertThat(source.fastForwardIfHeadMatches("draft", published, published, "alice", "Already current"))
                    .isEqualTo(published);
        }
    }

    @Test void aSignedTipCannotSmuggleAnInvalidIntermediateCommitIntoAcceptedAncestry() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        try (var source = new DslGitRepository().withSignatureService(signatures, "workspace");
             var target = new DslGitRepository().withSignatureService(signatures, "central")) {
            String accepted = target.commitDsl("main", "# accepted", "bob", "Accepted");
            String first = source.commitDsl("draft", "# first", "alice", "First");
            String malformed;
            try (var walk = new RevWalk(source.getGitRepository()); var inserter = source.getGitRepository().newObjectInserter()) {
                var parent = walk.parseCommit(ObjectId.fromString(first));
                var commit = new CommitBuilder(); commit.setTreeId(parent.getTree()); commit.setParentId(parent);
                commit.setAuthor(parent.getAuthorIdent()); commit.setCommitter(parent.getCommitterIdent()); commit.setMessage("Invalid intermediate");
                commit.setGpgSignature(new GpgSignature("invalid".getBytes(StandardCharsets.US_ASCII)));
                malformed = inserter.insert(commit).name(); inserter.flush();
                var ref = source.getGitRepository().updateRef("refs/heads/draft");
                ref.setExpectedOldObjectId(ObjectId.fromString(first)); ref.setNewObjectId(ObjectId.fromString(malformed));
                assertThat(ref.update()).isEqualTo(RefUpdate.Result.FAST_FORWARD);
            }
            String trustedTip = source.commitDsl("draft", "# trusted tip", "alice", "Trusted tip");
            assertThat(source.verifyCommit(trustedTip).status()).isEqualTo(SignatureStatus.VERIFIED);
            assertThatThrownBy(() -> GitHistoryTransfer.copyCommitClosure(source, target, trustedTip, List.of(accepted)))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(target.getHeadCommit("main")).isEqualTo(accepted);
            assertThat(target.listBranches()).extracting(DslBranch::name).containsExactly("main");
        }
    }
}
