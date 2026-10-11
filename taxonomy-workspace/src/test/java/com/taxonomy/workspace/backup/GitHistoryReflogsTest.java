package com.taxonomy.workspace.backup;

import com.taxonomy.backup.BackupCheckpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class GitHistoryReflogsTest {
    private static final BackupCheckpoint CHECK = () -> { };
    private static final String OLD = "0".repeat(40);
    private static final String NEW = "1".repeat(40);
    private static final String LINE = OLD + " " + NEW
            + " Original Äuthor <original@example.invalid> 315630245 +0200\tOriginal operation";
    @TempDir Path temporary;

    @Test void repeatedNativeEventsPreserveTheirOriginalIdentityOrderAndTime() throws Exception {
        Path log = Files.writeString(temporary.resolve("HEAD"), LINE + "\n" + LINE + "\n");
        var expected = new GitHistoryCapture.Reflog("HEAD", OLD, NEW, "Original Äuthor",
                "original@example.invalid", 315630245, 120, "Original operation");
        assertThat(GitHistoryReflogs.file(log, "HEAD", new GitHistoryReflogs.Budget(2), CHECK))
                .containsExactly(expected, expected);
    }

    @Test void anAbsentNativeLogHasNoEventsButADirectoryIsNotAnEmptyLog() throws Exception {
        Path log = temporary.resolve("HEAD");
        assertThat(GitHistoryReflogs.file(log, "HEAD", new GitHistoryReflogs.Budget(2), CHECK)).isEmpty();
        Files.createDirectory(log);
        assertThatThrownBy(() -> GitHistoryReflogs.file(log, "HEAD", new GitHistoryReflogs.Budget(2), CHECK))
                .isInstanceOf(IOException.class).hasMessageContaining("not a regular file");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unfinished", "old-id", "new-id", "author", "carriage-return", "nul"})
    void invalidNativeRecordsCannotBeAcceptedAsCompleteHistory(String defect) throws Exception {
        String line = switch (defect) {
            case "unfinished" -> LINE;
            case "old-id" -> "g" + LINE.substring(1) + "\n";
            case "new-id" -> LINE.substring(0, 41) + "g" + LINE.substring(42) + "\n";
            case "author" -> OLD + " " + NEW + " not-an-identity\tOriginal operation\n";
            case "carriage-return" -> LINE + "\r\n";
            case "nul" -> LINE + "\0\n";
            default -> throw new AssertionError(defect);
        };
        Path log = Files.writeString(temporary.resolve("HEAD"), line);
        assertThatThrownBy(() -> GitHistoryReflogs.file(log, "HEAD", new GitHistoryReflogs.Budget(2), CHECK))
                .isInstanceOf(IOException.class).hasMessageContaining("reflog");
    }
}
