package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.signing.GitCommitSignatureService;
import com.taxonomy.workspace.signing.SigningTestKeys;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adversarial provenance inputs must pass both object-hash proof and signature policy. */
class CommitProvenanceSignatureTest {
    @TempDir Path keys;

    @Test void signedEvidenceFromUnknownKeyCannotBecomeTrustedThroughAValidHash() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        try (var source = new DslGitRepository().withSignatureService(signatures, "source")) {
            var head = source.commitDsl("main", "# source", "alice", "Source");
            var evidence = CommitProvenance.capture("fork", source, "source", "main", head, null);
            assertThatThrownBy(() -> evidence.verify(GitCommitSignatureService.disabled()))
                    .isInstanceOf(IOException.class).hasMessageContaining("UNTRUSTED");
        }
    }

    @Test void recomputingSourceHashCannotHideAMalformedOpenPgpSignature() throws Exception {
        var signatures = SigningTestKeys.createService(keys);
        try (var source = new DslGitRepository().withSignatureService(signatures, "source");
             var formatter = new ObjectInserter.Formatter()) {
            var head = source.commitDsl("main", "# source", "alice", "Source");
            var valid = CommitProvenance.capture("fork", source, "source", "main", head, null).sources().getFirst();
            var malformed = new String(valid.rawCommit(), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PGP SIGNATURE-----", "-----BEGIN PGP BROKEN-----")
                    .getBytes(StandardCharsets.UTF_8);
            var rewritten = new CommitProvenance.Source(valid.repositoryId(), valid.branch(),
                    formatter.idFor(Constants.OBJ_COMMIT, malformed).name(), null, valid.dslBlobId(),
                    malformed, valid.rawTree());
            assertThatThrownBy(() -> new CommitProvenance(1, "fork", List.of(rewritten)).verify(signatures))
                    .isInstanceOf(IOException.class).hasMessageContaining("signature policy");
        }
    }

    @Test void malformedUnsignedSourceCommitProducesAnIoFailureEvenWithMatchingObjectHash() throws Exception {
        byte[] malformed = ("tree " + "z".repeat(40)
                + "\nauthor alice <alice@example.test> 1700000000 +0000"
                + "\ncommitter alice <alice@example.test> 1700000000 +0000\n\nsource\n")
                .getBytes(StandardCharsets.US_ASCII);
        try (var formatter = new ObjectInserter.Formatter()) {
            var evidence = new CommitProvenance(1, "fork", List.of(new CommitProvenance.Source(
                    "source", "main", formatter.idFor(Constants.OBJ_COMMIT, malformed).name(), null,
                    "a".repeat(40), malformed, new byte[] {1})));
            assertThatThrownBy(() -> evidence.verify(GitCommitSignatureService.disabled())).isInstanceOf(IOException.class);
        }
    }
}
