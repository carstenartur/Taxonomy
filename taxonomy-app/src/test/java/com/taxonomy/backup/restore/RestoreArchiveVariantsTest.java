package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.workspace.backup.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RestoreArchiveVariantsTest {
    private static final BackupCheckpoint CHECK = () -> { };
    private static final Map<String, Class<? extends Record>> TYPES = Map.of(
            "repository", GitHistoryCapture.HistoryRecord.class,
            "reflog", GitHistoryCapture.LogRecord.class,
            "original-reflog", GitHistoryCapture.OriginalLogRecord.class);

    @Test void nativeHistoryRecordsKeepRepositoryLiveLogAndOriginalProvenanceAsExactCompiledTypes() throws Exception {
        var history = history();
        var log = new GitHistoryCapture.Reflog("refs/heads/main", "0".repeat(40), "a".repeat(40),
                "Original", "source@example.invalid", 315_532_800, 60, "Original commit");
        var origin = new GitReflogProvenance.Origin("b".repeat(64), new BackupRepositoryKey("original", null), "original");
        var original = new GitHistoryCapture.OriginalLogRecord("original-reflog", new GitReflogProvenance.Event(origin, 0, log));
        var values = new ArrayList<Record>();
        read(document(history, new GitHistoryCapture.LogRecord("reflog", log), original), 3, CHECK, values::add);
        assertThat(values).containsExactly(history, new GitHistoryCapture.LogRecord("reflog", log), original);
    }

    @Test void unknownAndNonStringDiscriminatorsDuplicateFieldsAndSuppliedClassNamesAreRejected() throws Exception {
        String valid = document(history());
        for (String invalid : List.of(valid.replace("\"repository\"", "\"runtime-class\""),
                valid.replace("\"recordType\":\"repository\"", "\"recordType\":42"),
                valid.replace("\"recordType\":\"repository\"", "\"recordType\":\"repository\",\"recordType\":\"repository\""),
                valid.replace("\"recordType\":\"repository\"", "\"recordType\":\"repository\",\"@class\":\"java.lang.Runtime\""),
                valid.replace("\"objectCount\":1", "\"objectCount\":\"1\""))) {
            var visited = new ArrayList<Record>();
            assertThatThrownBy(() -> read(invalid, 10, CHECK, visited::add)).isInstanceOf(IOException.class)
                    .hasMessage("Invalid or unsupported portable restore dataset");
            assertThat(visited).isEmpty();
        }
    }

    @Test void mixedRecordsRetainTheSharedRowBoundaryAndOperationCancellation() throws Exception {
        var values = new ArrayList<Record>();
        assertThatThrownBy(() -> read(document(history(), history()), 1, CHECK, values::add))
                .isInstanceOf(IOException.class).hasMessageContaining("row limit");
        assertThat(values).containsExactly(history());
        try {
            assertThatThrownBy(() -> read(document(history()), 2, () -> { throw new InterruptedIOException("cancel"); }, ignored -> { }))
                    .isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    private static GitHistoryCapture.HistoryRecord history() {
        var state = new SnapshotContext.RepositoryState(Map.of("HEAD", "a".repeat(40), "refs/heads/main", "a".repeat(40)),
                "refs/heads/main", Map.of(), Set.of("a".repeat(40)));
        return new GitHistoryCapture.HistoryRecord("repository", "original", state, Map.of(), Map.of(), 1, null);
    }
    private static String document(Record... records) throws IOException {
        var json = PortableRows.json();
        var text = new StringBuilder(json.writeValueAsString(new PortableRows.Header(1, "git-history", BackupProfile.REPOSITORY_HISTORY))).append('\n');
        for (var record : records) text.append(json.writeValueAsString(record)).append('\n');
        return text.toString();
    }
    private static void read(String text, int rows, BackupCheckpoint check, RestoreArchiveRecords.Visitor<Record> visitor) throws IOException {
        RestoreArchiveRecords.readVariants(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "git-history",
                BackupProfile.REPOSITORY_HISTORY, TYPES, rows, check, visitor);
    }
}
