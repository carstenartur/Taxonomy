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
import java.util.function.Consumer;
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
        synchronized Reference accept(ScenarioLlmPlayback.Call call) throws IOException {
            if (failed) throw new IOException("Call journal failed; no later exchange can be accepted");
            try {
                long start = Files.size(path);
                append(path, call);
                long length = Files.size(path) - start;
                if (length < 1 || length > MAX_RECORD_BYTES + 1L)
                    throw new IOException("Invalid accepted call journal record length");
                return new Reference(path, start, (int) length, call.ruleId(), call.promptSha256(), call.responseSha256());
            }
            catch (IOException failure) { failed = true; throw failure; }
        }
    }

    /** No payload or open descriptor survives acceptance. File bytes remain the sole full evidence. */
    record Reference(Path path, long offset, int length, String ruleId, String promptSha256, String responseSha256) {
        ScenarioLlmPlayback.Call read() throws IOException {
            if (offset < 0 || length < 1 || length > MAX_RECORD_BYTES + 1)
                throw new IOException("Invalid call journal reference bounds");
            var bytes = ByteBuffer.allocate(length);
            try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.position(offset);
                while (bytes.hasRemaining()) {
                    if (channel.read(bytes) < 0) throw new IOException("Incomplete referenced call journal record");
                }
            }
            if (bytes.array()[length - 1] != '\n') throw new IOException("Incomplete referenced call journal record");
            var call = decode(java.util.Arrays.copyOf(bytes.array(), length - 1));
            if (!ruleId.equals(call.ruleId()) || !promptSha256.equals(call.promptSha256())
                    || !responseSha256.equals(call.responseSha256()))
                throw new IllegalArgumentException("Referenced call journal identity mismatch");
            return call;
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
        scan(journal, calls::add);
        return List.copyOf(calls);
    }

    private static ScenarioLlmPlayback.Call decode(byte[] record) {
        var call = JSON.readerFor(ScenarioLlmPlayback.Call.class)
                .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .<ScenarioLlmPlayback.Call>readValue(record);
        if (call == null || call.ruleId() == null || call.ruleId().isBlank() || call.prompt() == null || call.response() == null
                || !ScenarioLlmPlayback.sha256(call.prompt()).equals(call.promptSha256())
                || !ScenarioLlmPlayback.sha256(call.response()).equals(call.responseSha256()))
            throw new IllegalArgumentException("Call journal fingerprint or identity mismatch");
        return call;
    }

    private static void scan(Path journal, Consumer<ScenarioLlmPlayback.Call> accepted) throws IOException {
        try (var input = new BufferedInputStream(Files.newInputStream(journal))) {
            var record = new ByteArrayOutputStream();
            for (int value; (value = input.read()) != -1;) {
                if (value != '\n') {
                    if (record.size() >= MAX_RECORD_BYTES) throw new IllegalArgumentException("Oversized call journal record");
                    record.write(value);
                    continue;
                }
                accepted.accept(decode(record.toByteArray()));
                record.reset();
            }
            // A killed writer can leave a final unterminated record. No response
            // was returned for it; all earlier accepted exchanges remain intact.
        }
    }
    public static void assemble(Path journal, Path artifact) throws IOException {
        Path temporary = Files.createTempFile(artifact.getParent(), ".scenario-calls-", ".json");
        try {
            try (var output = Files.newOutputStream(temporary);
                 var generator = JSON.writerWithDefaultPrettyPrinter().createGenerator(output)) {
                generator.writeStartArray();
                scan(journal, call -> JSON.writeValue(generator, call));
                generator.writeEndArray();
            }
            Files.move(temporary, artifact, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
