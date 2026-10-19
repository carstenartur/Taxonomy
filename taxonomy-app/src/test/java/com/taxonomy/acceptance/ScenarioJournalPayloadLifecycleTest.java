package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Accepted evidence stays complete on disk without retaining every payload in the driver heap. */
class ScenarioJournalPayloadLifecycleTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fullPublicSnapshotsKeepOwnThenAdoptedCallsButArrivalOrderedRejectedAndDelegatedPrompts(boolean journaled) throws Exception {
        var playback = new ScenarioLlmPlayback(ReformulationScenarioCorpus.flood());
        Path journal = directory.resolve("ordered.jsonl");
        if (journaled) playback.recordCallsTo(journal);
        String own = ScenarioLlmPlaybackTest.prompt(playback, playback.fixture().path("replies").get(0));
        String original = playback.fixture().at("/requirement/text").asText();
        String adopted = original + " Explicitly adopted wording.";
        String first = playback.respond(own);
        playback.registerAdoptedSource(adopted);
        String delegated = own.replace(original, adopted);
        String second = playback.respond(delegated);
        String third = playback.respond(own + "\nA later own arrival.");
        String rejected = own.replace(original, "An unknown requirement.");
        assertThatThrownBy(() -> playback.respond(rejected)).isInstanceOf(IllegalArgumentException.class);
        var calls = playback.calls();
        var prompts = playback.prompts();
        assertThat(calls).extracting(ScenarioLlmPlayback.Call::prompt)
                .containsExactly(own, own + "\nA later own arrival.", delegated);
        assertThat(calls).extracting(ScenarioLlmPlayback.Call::response).containsExactly(first, third, second);
        assertThat(prompts).containsExactly(own, delegated, own + "\nA later own arrival.", rejected);
        assertThat(playback.callCount()).isEqualTo(3);
        assertThat(playback.promptCount()).isEqualTo(4);
        assertThatThrownBy(() -> calls.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> prompts.clear()).isInstanceOf(UnsupportedOperationException.class);
        playback.respond(own + "\nNew arrival after taking public snapshots.");
        assertThat(calls).hasSize(3);
        assertThat(prompts).hasSize(4);
        if (journaled) {
            assertThat(ScenarioCallJournal.read(journal)).extracting(ScenarioLlmPlayback.Call::prompt)
                    .containsExactly(own, delegated, own + "\nA later own arrival.", own + "\nNew arrival after taking public snapshots.");
            // Returned API snapshots are full values, not lazy references to a file that can later change.
            Files.writeString(journal, "corrupt");
            assertThat(calls.getFirst().prompt()).isEqualTo(own);
            assertThat(prompts.get(1)).isEqualTo(delegated);
            assertThatThrownBy(playback::calls).isInstanceOf(java.io.UncheckedIOException.class);
        }
    }

    @Test void nullRejectedAttemptRetainsTheOriginalImmutablePromptSnapshotFailure() throws Exception {
        var playback = new ScenarioLlmPlayback(ReformulationScenarioCorpus.flood());
        assertThatThrownBy(() -> playback.respond(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(playback.callCount()).isZero();
        assertThat(playback.promptCount()).isEqualTo(1);
        assertThatThrownBy(playback::prompts).isInstanceOf(NullPointerException.class);
    }

    @Test void positionalReferenceChecksCompletePayloadAndExpectedIdentityWithoutReadingOtherRecords() throws Exception {
        Path journal = directory.resolve("references.jsonl");
        Files.createFile(journal);
        var writer = new ScenarioCallJournal.Writer(journal);
        var first = call("one", "水é💧 prompt one", "réponse one");
        var second = call("two", "水é💧 prompt two", "réponse two");
        var one = writer.accept(first);
        var two = writer.accept(second);
        assertThat(one.read()).isEqualTo(first);
        assertThat(two.read()).isEqualTo(second);
        // Valid hashes in another same-sized Call are insufficient to impersonate this accepted record.
        String replacement = json.writeValueAsString(second) + "\n";
        assertThat(replacement.getBytes(StandardCharsets.UTF_8)).hasSize(one.length());
        try (var file = FileChannel.open(journal, StandardOpenOption.WRITE)) {
            var bytes = ByteBuffer.wrap(replacement.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) file.write(bytes);
        }
        assertThatThrownBy(one::read).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity");
        assertThat(two.read()).isEqualTo(second);
        Files.writeString(journal, json.writeValueAsString(first));
        assertThatThrownBy(one::read).isInstanceOf(java.io.IOException.class).hasMessageContaining("Incomplete");
        assertThatThrownBy(() -> new ScenarioCallJournal.Reference(journal, 0, 2 * 1024 * 1024 + 2,
                first.ruleId(), first.promptSha256(), first.responseSha256()).read())
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("bounds");
        Files.delete(journal);
        assertThatThrownBy(two::read).isInstanceOf(java.nio.file.NoSuchFileException.class);
    }

    @Test void corruptCompleteRecordDoesNotReplaceAnExistingFinalArtifactAndTornTailRemainsRecoverable() throws Exception {
        Path journal = directory.resolve("atomic.jsonl"), archive = directory.resolve("archive.json");
        var first = call("one", "full 水 prompt", "full é response");
        Files.writeString(journal, json.writeValueAsString(first) + "\n{\"unfinished");
        ScenarioCallJournal.assemble(journal, archive);
        assertThat(json.readTree(archive.toFile())).isEqualTo(json.valueToTree(List.of(first)));
        byte[] retained = Files.readAllBytes(archive);
        Files.writeString(journal, json.writeValueAsString(first) + "\n"
                + json.writeValueAsString(new ScenarioLlmPlayback.Call("bad", first.promptSha256(),
                first.responseSha256(), "altered", first.response())) + "\n");
        assertThatThrownBy(() -> ScenarioCallJournal.assemble(journal, archive))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fingerprint");
        assertThat(Files.readAllBytes(archive)).isEqualTo(retained);
        try (var entries = Files.list(directory)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.startsWith(".scenario-calls-"));
        }
    }

    private static ScenarioLlmPlayback.Call call(String id, String prompt, String response) throws Exception {
        return new ScenarioLlmPlayback.Call(id, fingerprint(prompt), fingerprint(response), prompt, response);
    }

    private static String fingerprint(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void fsyncedJournalReleasesAcceptedUnicodePayloadsInABoundedHeap() throws Exception {
        Path log = directory.resolve("payload-lifecycle.log");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(), directory.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(child.waitFor(120, TimeUnit.SECONDS)).as("Journal lifecycle child timeout: %s", log).isTrue();
            assertThat(child.exitValue()).as(Files.readString(log)).isZero();
            assertThat(Files.readString(log)).contains("JOURNAL_PAYLOAD_LIFECYCLE_OK calls=192");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    public static final class Probe {
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final int CALLS = 192;

        public static void main(String[] args) throws Exception {
            Path folder = Path.of(args[0]);
            Path journal = folder.resolve("large-calls.jsonl");
            var fixture = JSON.createObjectNode().put("schemaVersion", 1);
            fixture.putObject("requirement").put("text", "Retain full Unicode evidence.");
            var rule = fixture.putArray("replies").addObject().put("id", "only-rule")
                    .put("task", "categories").put("parentScore", 100);
            rule.putArray("keys").add("BP");
            rule.putObject("answers").putObject("BP").put("score", 100)
                    .put("reason", "Réponse 水💧".repeat(8_000));
            // Playback always validates its complete relation corpus, even for a scoring-only probe.
            fixture.putObject("bindings").put("BP-1", "process").put("IP", "products").put("IP-1", "evidence");
            var relations = fixture.putObject("relationPlayback");
            var contributions = relations.putArray("sourceContributions");
            contributions.addObject().put("nodeId", "BP-1").put("text", "Retain evidence").put("quote", "Retain");
            contributions.addObject().put("nodeId", "IP-1").put("text", "Unicode evidence").put("quote", "evidence");
            var paths = relations.putObject("paths");
            paths.putArray("BP-1").add("BP").add("BP-1");
            paths.putArray("IP-1").add("IP").add("IP-1");
            relations.putArray("claims").addObject().put("sourceId", "BP-1").put("targetId", "IP-1")
                    .put("type", "CONSUMES").put("quote", "evidence");
            var playback = new ScenarioLlmPlayback(fixture);
            playback.recordCallsTo(journal);
            long offset = 0;
            for (int index = 0; index < CALLS; index++) {
                String prompt = prompt(index);
                String response = playback.respond(prompt);
                long next = Files.size(journal);
                // Validate the just-fsynced record before the next provider exchange, without loading older calls.
                var call = readRecord(journal, offset, Math.toIntExact(next - offset));
                verify(call, index);
                if (!call.prompt().equals(prompt) || !call.response().equals(response))
                    throw new AssertionError("Accepted response differs from the recoverable complete record");
                if (playback.callCount() != index + 1 || playback.promptCount() != index + 1)
                    throw new AssertionError("Cheap counts lost accepted evidence");
                offset = next;
                if ((index + 1) % 32 == 0) System.out.println("Accepted and validated calls=" + (index + 1));
            }
            playback.verifyCoverage(CALLS);
            Path archive = folder.resolve("large-calls.json");
            ScenarioCallJournal.assemble(journal, archive);
            // Final public archive remains the complete original Call schema; parse only one record at a time.
            try (var input = JSON.createParser(Files.newInputStream(archive))) {
                if (input.nextToken() != JsonToken.START_ARRAY) throw new AssertionError("Missing archive array");
                int count = 0;
                while (input.nextToken() != JsonToken.END_ARRAY) {
                    if (input.currentToken() != JsonToken.START_OBJECT) throw new AssertionError("Missing full Call record");
                    verify(JSON.readerFor(ScenarioLlmPlayback.Call.class)
                            .without(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .<ScenarioLlmPlayback.Call>readValue(input), count++);
                }
                if (count != CALLS || input.nextToken() != null) throw new AssertionError("Incomplete or trailing archive");
            }
            System.out.println("JOURNAL_PAYLOAD_LIFECYCLE_OK calls=" + playback.callCount());
        }

        private static String prompt(int index) {
            return "Analyse and distribute the parent relevance score of 100\n"
                    + "Business Requirement: Retain full Unicode evidence.\n\nNodes to evaluate:\n"
                    + "Respond ONLY with a valid JSON object using EXACTLY these keys: BP\n"
                    + "Unique observation " + index + ": " + "水é💧".repeat(45_000);
        }

        private static ScenarioLlmPlayback.Call readRecord(Path journal, long offset, int size) throws Exception {
            if (size > 2 * 1024 * 1024 + 1) throw new AssertionError("Record exceeds existing journal quota");
            var bytes = ByteBuffer.allocate(size);
            try (var file = FileChannel.open(journal, StandardOpenOption.READ)) {
                file.position(offset);
                while (bytes.hasRemaining()) if (file.read(bytes) < 0) throw new AssertionError("Uncommitted journal record");
            }
            if (bytes.array()[size - 1] != '\n') throw new AssertionError("Record is not complete");
            return JSON.readValue(new String(bytes.array(), 0, size - 1, StandardCharsets.UTF_8), ScenarioLlmPlayback.Call.class);
        }

        private static void verify(ScenarioLlmPlayback.Call call, int index) throws Exception {
            if (!call.ruleId().equals("only-rule") || !call.prompt().equals(prompt(index))
                    || !call.promptSha256().equals(fingerprint(call.prompt()))
                    || !call.responseSha256().equals(fingerprint(call.response())))
                throw new AssertionError("Complete payload, order or fingerprint was lost");
            var body = JSON.readTree(call.response());
            var answer = JSON.readTree(body.at("/choices/0/message/content").asText());
            if (answer.at("/BP/score").asInt() != 100
                    || !answer.at("/BP/reason").asText().equals("Réponse 水💧".repeat(8_000)))
                throw new AssertionError("Provider wire evidence was changed");
        }
    }
}
