package com.taxonomy.support;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/** Bounded assertion diagnostics; full process logs remain untouched for CI artifacts. */
public final class ProcessTestDiagnostics {
    private static final int MAX_TAIL_BYTES = 8192;
    private static final int MAX_TAIL_LINES = 80;

    private ProcessTestDiagnostics() { }

    public static void assertCompleted(int exitCode, Path log, String marker) {
        Objects.requireNonNull(marker, "completion marker");
        if (marker.isBlank()) throw new IllegalArgumentException("Completion marker must not be blank");
        if (exitCode != 0) {
            throw new AssertionError("Application exit code " + exitCode + "; " + describe(log));
        }
        try {
            if (!containsMarker(log, marker)) {
                throw new AssertionError("Missing application completion marker '" + marker + "'; " + describe(log));
            }
        } catch (IOException failure) {
            throw new AssertionError("Cannot verify application completion marker '" + marker + "'; " + describe(log), failure);
        }
    }

    /** Also usable when a child times out: never let a missing log hide the primary failure. */
    public static String describe(Path log) {
        String location = "Full application log: " + log.toAbsolutePath() + "\n";
        try (var channel = Files.newByteChannel(log)) {
            long size = channel.size();
            int length = (int) Math.min(size, MAX_TAIL_BYTES);
            long offset = size - length;
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // Read only a bounded tail, even for a single exceptionally long log line.
            }
            int start = 0;
            byte[] bytes = buffer.array();
            if (offset > 0) {
                // A byte limit may start inside a UTF-8 sequence. Drop continuation bytes.
                while (start < buffer.position() && (bytes[start] & 0xC0) == 0x80) start++;
            }
            String tail = new String(bytes, start, buffer.position() - start, StandardCharsets.UTF_8);
            String[] lines = tail.split("\\R", -1);
            int firstLine = Math.max(0, lines.length - MAX_TAIL_LINES);
            String omitted = offset > 0 || firstLine > 0 ? "[... earlier log omitted; see full artifact ...]\n" : "";
            return location + omitted + String.join("\n", Arrays.copyOfRange(lines, firstLine, lines.length));
        } catch (IOException failure) {
            return location + "[Log unavailable: " + failure.getClass().getSimpleName() + "]";
        }
    }

    private static boolean containsMarker(Path log, String marker) throws IOException {
        try (var reader = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            String previous = "";
            int read;
            while ((read = reader.read(buffer)) != -1) {
                String window = previous + new String(buffer, 0, read);
                if (window.contains(marker)) return true;
                // Include markers split across read boundaries, not just markers in the tail.
                previous = window.substring(Math.max(0, window.length() - marker.length() + 1));
            }
            return false;
        }
    }
}
