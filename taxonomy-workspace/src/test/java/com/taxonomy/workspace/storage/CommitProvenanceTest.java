package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.signing.SigningTestKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CommitProvenanceTest {
    @TempDir Path keys;

    @Test
    void independentSnapshotRetainsVerifiableSourceCommitAndTreeWithoutCopyingHistory() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        try (var source = new DslGitRepository().withSignatureService(signatures, "source");
             var target = new DslGitRepository().withSignatureService(signatures, "target")) {
            String original = source.commitDsl("main", "# model", "alice", "Original");
            var evidence = CommitProvenance.capture("fork", source, "central-a", "main", original, null);
            String copied = target.commitDslIfHeadMatches("draft", null, "# model", "bob", "Fork", evidence);
            target.verifySnapshot(copied);
            assertThat(target.getCommitCount("draft")).isEqualTo(1);
            assertThat(target.getProvenance(copied).sources().getFirst().commitId()).isEqualTo(original);
            assertThat(target.getProvenance(copied).sources().getFirst().repositoryId()).isEqualTo("central-a");
            assertThat(target.getDslAtCommit(copied)).isEqualTo("# model");
            assertThatThrownBy(() -> target.getDslAtCommit(original)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void evidenceCannotSubstituteAnotherCommitOrTreeUnderAnOriginalSignature() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        try (var source = new DslGitRepository().withSignatureService(signatures, "source")) {
            String original = source.commitDsl("main", "# model", "alice", "Original");
            var evidence = CommitProvenance.capture("publish", source, "workspace-a", "draft", original, null);
            var valid = evidence.sources().getFirst();
            var wrongId = new CommitProvenance.Source(valid.repositoryId(), valid.branch(), "a".repeat(40),
                    null, valid.dslBlobId(), valid.rawCommit(), valid.rawTree());
            assertThatThrownBy(() -> new CommitProvenance(1, "publish", List.of(wrongId)).verify(signatures))
                    .isInstanceOf(IOException.class);
            byte[] modifiedTree = valid.rawTree(); modifiedTree[modifiedTree.length - 1] ^= 1;
            var wrongTree = new CommitProvenance.Source(valid.repositoryId(), valid.branch(), valid.commitId(),
                    null, valid.dslBlobId(), valid.rawCommit(), modifiedTree);
            assertThatThrownBy(() -> new CommitProvenance(1, "publish", List.of(wrongTree)).verify(signatures))
                    .isInstanceOf(IOException.class);
            evidence.verify(signatures); // accessors do not expose the original mutable arrays
        }
    }

    @Test
    void repeatedSynchronizationKeepsEvidenceBoundedInsteadOfEmbeddingOlderEvidence() throws Exception {
        try (var repository = new DslGitRepository()) {
            String head = repository.commitDsl("draft", "# model", "alice", "Initial");
            int firstLength = 0;
            for (int i = 0; i < 12; i++) {
                var evidence = CommitProvenance.capture("pull", repository, "upstream", "main", head, null);
                if (i == 0) firstLength = evidence.toBytes().length;
                head = repository.commitDsl("draft", "# model " + i, "alice", "Integrate", evidence);
            }
            assertThat(repository.getProvenance(head).toBytes().length).isLessThan(firstLength + 1024);
        }
    }
}
