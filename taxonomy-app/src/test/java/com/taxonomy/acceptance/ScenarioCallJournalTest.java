package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.*;

class ScenarioCallJournalTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test void acceptedExchangeIsRecoverableBeforeTheDriverObservesItsCycle() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        var journal = directory.resolve("calls.jsonl");
        playback.recordCallsTo(journal);
        String prompt = ScenarioLlmPlaybackTest.prompt(playback, playback.fixture().path("replies").get(0));
        String response = playback.respond(prompt);
        // No cycle checkpoint/finalization runs: simulate interruption after the provider returned.
        var recovered = ScenarioCallJournal.read(journal);
        assertThat(recovered).containsExactlyElementsOf(playback.calls());
        assertThat(recovered.getFirst().prompt()).isEqualTo(prompt);
        assertThat(recovered.getFirst().response()).isEqualTo(response);
        assertThat(recovered.getFirst().promptSha256()).isEqualTo(ScenarioLlmPlayback.sha256(prompt));
        assertThat(recovered.getFirst().responseSha256()).isEqualTo(ScenarioLlmPlayback.sha256(response));
        var artifact = directory.resolve("llm-calls.json");
        ScenarioCallJournal.assemble(journal, artifact);
        assertThat(json.readTree(artifact.toFile())).isEqualTo(json.valueToTree(playback.calls()));
    }

    @Test void appendsWithoutRewritingPriorExchangesAndRecoversOnlyCompleteTailRecords() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        var journal = directory.resolve("calls.jsonl");
        playback.recordCallsTo(journal);
        String prompt = ScenarioLlmPlaybackTest.prompt(playback, playback.fixture().path("replies").get(0));
        playback.respond(prompt);
        byte[] first = Files.readAllBytes(journal);
        playback.respond(prompt);
        assertThat(Files.readAllBytes(journal)).startsWith(first);
        Files.writeString(journal, "{\"ruleId\":\"interrupted", StandardOpenOption.APPEND);
        assertThat(ScenarioCallJournal.read(journal)).containsExactlyElementsOf(playback.calls());
    }

    @Test void completeCorruptRecordsFailRatherThanSilentlyDiscardingEvidence() throws Exception {
        var journal = directory.resolve("calls.jsonl");
        Files.writeString(journal, json.writeValueAsString(new ScenarioLlmPlayback.Call(
                "known", "a".repeat(64), "b".repeat(64), "prompt", "response")) + "\n");
        assertThatThrownBy(() -> ScenarioCallJournal.read(journal)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
    }

    @Test void cannotAcceptARecordThatExceedsTheRecoveryReaderLimit() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        var journal = directory.resolve("calls.jsonl");
        playback.recordCallsTo(journal);
        String prompt = ScenarioLlmPlaybackTest.prompt(playback, playback.fixture().path("replies").get(0))
                + " ".repeat(2 * 1024 * 1024);
        assertThatThrownBy(() -> playback.respond(prompt)).isInstanceOf(IllegalArgumentException.class);
        assertThat(playback.calls()).isEmpty();
        assertThat(ScenarioCallJournal.read(journal)).isEmpty();
    }

    @Test void appendFailurePoisonsTheWriterWithoutCompletingItsTornTail() throws Exception {
        var fixture = ReformulationScenarioCorpus.flood();
        var playback = new ScenarioLlmPlayback(fixture);
        var journal = directory.resolve("calls.jsonl");
        playback.recordCallsTo(journal);
        String prompt = ScenarioLlmPlaybackTest.prompt(playback, playback.fixture().path("replies").get(0));
        playback.respond(prompt);
        String original = fixture.at("/requirement/text").asText();
        String adopted = original + "\n\nReviewed offer text.";
        playback.registerAdoptedSource(adopted);
        var accepted = playback.calls();
        try (var writes = org.mockito.Mockito.mockStatic(ScenarioCallJournal.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            writes.when(() -> ScenarioCallJournal.append(org.mockito.ArgumentMatchers.eq(journal), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        Files.writeString(journal, "{\"interrupted", StandardOpenOption.APPEND);
                        throw new java.io.IOException("interrupted journal write");
                    });
            assertThatThrownBy(() -> playback.respond(prompt)).isInstanceOf(IllegalArgumentException.class);
        }
        long tornSize = Files.size(journal);
        assertThatThrownBy(() -> playback.respond(prompt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> playback.respond(prompt.replace(original, adopted))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.size(journal)).isEqualTo(tornSize);
        assertThat(ScenarioCallJournal.read(journal)).containsExactlyElementsOf(accepted);
        assertThat(playback.calls()).containsExactlyElementsOf(accepted);
    }
}
