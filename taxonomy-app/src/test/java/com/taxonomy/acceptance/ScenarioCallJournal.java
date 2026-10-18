package com.taxonomy.acceptance;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** Recoverable evidence captured at the remote-response acceptance boundary. */
public final class ScenarioCallJournal {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RECORD_BYTES = 2 * 1024 * 1024;
    private ScenarioCallJournal() { }

    /** Shared by all source revisions using the same append-only evidence file. */
    static final class Writer {
        private final Path path;
        private boolean failed;
        Writer(Path path) { this.path = path; }
        synchronized void accept(ScenarioLlmPlayback.Call call) throws IOException {
            if (failed) throw new IOException("Call journal failed; no later exchange can be accepted");
            try { append(path, call); }
            catch (IOException failure) { failed = true; throw failure; }
        }
    }

    static void append(Path journal, ScenarioLlmPlayback.Call call) throws IOException {
        byte[] record = (JSON.writeValueAsString(call) + "\n").getBytes(StandardCharsets.UTF_8);
        if (record.length - 1 > MAX_RECORD_BYTES) throw new IllegalArgumentException("Oversized call journal record");
        try (var channel = FileChannel.open(journal, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            var bytes = ByteBuffer.wrap(record);
            while (bytes.hasRemaining()) channel.write(bytes);
            // The response is not accepted until its complete evidence is recoverable.
            channel.force(false);
        }
    }

    public static List<ScenarioLlmPlayback.Call> read(Path journal) throws IOException {
        var calls = new ArrayList<ScenarioLlmPlayback.Call>();
        try (var input = new BufferedInputStream(Files.newInputStream(journal))) {
            var record = new ByteArrayOutputStream();
            for (int value; (value = input.read()) != -1;) {
                if (value != '\n') {
                    if (record.size() >= MAX_RECORD_BYTES) throw new IllegalArgumentException("Oversized call journal record");
                    record.write(value);
                    continue;
                }
                var call = JSON.readValue(record.toByteArray(), ScenarioLlmPlayback.Call.class);
                if (call.ruleId() == null || call.ruleId().isBlank() || call.prompt() == null || call.response() == null
                        || !ScenarioLlmPlayback.sha256(call.prompt()).equals(call.promptSha256())
                        || !ScenarioLlmPlayback.sha256(call.response()).equals(call.responseSha256()))
                    throw new IllegalArgumentException("Call journal fingerprint or identity mismatch");
                calls.add(call);
                record.reset();
            }
            // A killed writer can leave a final unterminated record. No response
            // was returned for it; all earlier accepted exchanges remain intact.
        }
        return List.copyOf(calls);
    }
    public static void assemble(Path journal, Path artifact) throws IOException {
        var calls = read(journal);
        Path temporary = Files.createTempFile(artifact.getParent(), ".scenario-calls-", ".json");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), calls);
            Files.move(temporary, artifact, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
