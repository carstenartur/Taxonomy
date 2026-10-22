package com.taxonomy.editor;

import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.workspace.signing.SigningTestKeys;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static com.taxonomy.workspace.signing.SignatureStatus.VERIFIED;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureCheckpointSigningTest {
    @TempDir Path keys;

    @Test
    void retryUnderBackgroundAuthenticationReusesPublishedSignedCheckpointEvenAfterLaterEdit() throws Exception {
        try (var repository = new DslGitRepository().withSignatureService(SigningTestKeys.createService(keys), "ws-alice")) {
            String initial = repository.commitDsl("draft", "# original", "alice", "Initial");
            var intent = new EditorJournal.Checkpoint("command-1", "alice", Instant.now().toString(), "Reviewed",
                    "fingerprint-1", 0, 1, initial, "# changed", null, false, false, null, "test");
            var writer = new ArchitectureCheckpointWriter();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("alice", "unused", List.of()));
            var first = writer.write(repository, "scope-1", "draft", intent);
            assertThat(repository.verifyCommit(first.commitId()).status()).isEqualTo(VERIFIED);
            String later = repository.commitDsl("draft", "# subsequent", "bob", "Later edit");
            SecurityContextHolder.clearContext();

            var recovered = writer.write(repository, "scope-1", "draft", intent);

            assertThat(recovered.commitId()).isEqualTo(first.commitId());
            assertThat(repository.getHeadCommit("draft")).isEqualTo(later);
            assertThat(repository.getCommitCount("draft")).isEqualTo(3);
        } finally { SecurityContextHolder.clearContext(); }
    }
}
