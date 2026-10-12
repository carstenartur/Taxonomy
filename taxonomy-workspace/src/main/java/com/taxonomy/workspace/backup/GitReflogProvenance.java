package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import org.eclipse.jgit.lib.Repository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static com.taxonomy.workspace.backup.GitTreeCapture.failure;

/** Original imported events, distinct from the target repository's live operation audit. */
public final class GitReflogProvenance {
    private GitReflogProvenance() { }
    public record Origin(String archiveSha256, BackupRepositoryKey repository, String archiveId) {
        public Origin {
            Objects.requireNonNull(repository);
            if (archiveSha256 == null || !archiveSha256.matches("[0-9a-f]{64}") || archiveId == null
                    || !archiveId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || archiveId.equals(".") || archiveId.equals(".."))
                throw new IllegalArgumentException("Invalid original Git event origin");
        }
    }
    public record Event(Origin origin, int ordinal, GitHistoryCapture.Reflog entry) {
        public Event {
            Objects.requireNonNull(origin); Objects.requireNonNull(entry);
            if (ordinal < 0 || ordinal >= BackupLimits.MAX_ITEMS) throw new IllegalArgumentException("Invalid original Git event position");
        }
    }
    public static List<Event> original(Origin origin, List<GitHistoryCapture.Reflog> entries) {
        Objects.requireNonNull(origin);
        if (entries.size() > BackupLimits.MAX_ITEMS) throw new IllegalArgumentException("Too many original Git events");
        return java.util.stream.IntStream.range(0, entries.size()).mapToObj(index -> new Event(origin, index, entries.get(index))).toList();
    }
    static List<Event> canonical(List<Event> events, int maximum, BackupCheckpoint checkpoint) throws IOException {
        if (maximum < 0 || maximum > BackupLimits.MAX_ITEMS || events.size() > maximum)
            throw failure("Original Git event limit exceeded");
        var groups = new TreeMap<String, TreeMap<Integer, Event>>(); var budget = new GitHistoryReflogs.Budget(maximum);
        long bytes = 0;
        for (var event : events) {
            checkpoint.check(); Objects.requireNonNull(event); validate(event.entry()); budget.add(event.entry());
            int length = encoded(event).length;
            if (length > GitReflogProvenanceStore.MAX_EVENT_BYTES || length > GitHistoryReflogs.MAX_TOTAL_BYTES - bytes)
                throw failure("Original Git event byte limit exceeded");
            bytes += length;
            var group = groups.computeIfAbsent(originId(event.origin()), ignored -> new TreeMap<>());
            if (group.put(event.ordinal(), event) != null) throw failure("Duplicate original Git event position");
        }
        var result = new ArrayList<Event>(events.size());
        for (var group : groups.values()) {
            int next = 0; Origin origin = null;
            for (var event : group.values()) {
                checkpoint.check();
                if (event.ordinal() != next++ || origin != null && !origin.equals(event.origin()))
                    throw failure("Incomplete or inconsistent original Git event origin");
                origin = event.origin(); result.add(event);
            }
        }
        checkpoint.check(); return List.copyOf(result);
    }
    private static void validate(GitHistoryCapture.Reflog entry) throws IOException {
        if (entry.ref() == null || !entry.ref().equals("HEAD") && (!entry.ref().startsWith("refs/") || !Repository.isValidRefName(entry.ref()))
                || entry.ref().length() > 1_024 || entry.oldId() == null || !entry.oldId().matches("[0-9a-f]{40}")
                || entry.newId() == null || !entry.newId().matches("[0-9a-f]{40}") || entry.name() == null || entry.email() == null
                || entry.message() == null || entry.offsetMinutes() < -18 * 60 || entry.offsetMinutes() > 18 * 60
                || controls(entry.name()) || controls(entry.email())) throw failure("Invalid original Git event");
        try { Instant.ofEpochSecond(entry.epochSeconds()); }
        catch (RuntimeException invalid) { throw failure("Invalid original Git event time"); }
    }
    private static boolean controls(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0;
    }
    static byte[] encoded(Object value) throws IOException { return PortableRows.json().writeValueAsBytes(value); }
    static String originId(Origin origin) throws IOException { return digest(encoded(origin)); }
    static String repositoryKey(BackupRepositoryKey key) throws IOException { return digest(encoded(key)); }
    static String eventId(String repositoryKey, String origin, int ordinal) {
        return digest((repositoryKey + "\0" + origin + "\0" + ordinal).getBytes(StandardCharsets.US_ASCII));
    }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
