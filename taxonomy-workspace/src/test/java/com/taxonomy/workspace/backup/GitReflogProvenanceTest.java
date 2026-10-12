package com.taxonomy.workspace.backup;

import com.taxonomy.backup.BackupRepositoryKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class GitReflogProvenanceTest {
    private static final GitReflogProvenance.Origin ORIGIN = new GitReflogProvenance.Origin(
            "a".repeat(64), new BackupRepositoryKey("original", null), "original-history");

    @ParameterizedTest
    @ValueSource(strings = {"ref", "name-newline", "email-carriage-return", "name-nul", "time", "offset"})
    void importedEvidenceRejectsInvalidGitIdentityAndTime(String defect) {
        var entry = new GitHistoryCapture.Reflog(defect.equals("ref") ? "refs/heads/../foreign" : "HEAD",
                "0".repeat(40), "1".repeat(40),
                switch (defect) { case "name-newline" -> "A\nB"; case "name-nul" -> "A\0B"; default -> "Original author"; },
                defect.equals("email-carriage-return") ? "a\rb@example.invalid" : "original@example.invalid",
                defect.equals("time") ? Long.MAX_VALUE : 315630245,
                defect.equals("offset") ? 1081 : 120, "Original operation");
        var evidence = GitReflogProvenance.original(ORIGIN, List.of(entry));
        assertThatThrownBy(() -> GitReflogProvenance.canonical(evidence, 10, () -> { }))
                .isInstanceOf(IOException.class).hasMessageContaining("Invalid original Git event");
    }

    @Test void validOriginalEvidenceIsOrderedByItsSavedPositionWithoutCollapsingRepeatedOperations() throws Exception {
        var entry = new GitHistoryCapture.Reflog("HEAD", "0".repeat(40), "1".repeat(40),
                "Original Äuthor", "original@example.invalid", 315630245, 120, "Original operation");
        var evidence = GitReflogProvenance.original(ORIGIN, List.of(entry, entry));
        assertThat(GitReflogProvenance.canonical(List.of(evidence.get(1), evidence.get(0)), 2, () -> { }))
                .containsExactlyElementsOf(evidence);
        assertThatThrownBy(() -> GitReflogProvenance.canonical(List.of(evidence.get(0), evidence.get(0)), 2, () -> { }))
                .isInstanceOf(IOException.class).hasMessageContaining("Duplicate original Git event position");
    }
}
