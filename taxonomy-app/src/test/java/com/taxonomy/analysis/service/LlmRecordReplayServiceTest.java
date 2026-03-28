package com.taxonomy.analysis.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LlmRecordReplayService}.
 * Uses a temporary directory for all recordings — no real filesystem side-effects.
 */
class LlmRecordReplayServiceTest {

    @TempDir
    Path tempDir;

    // ── Helper ────────────────────────────────────────────────────────────────

    private LlmRecordReplayService service(boolean replay, boolean record,
                                            String fallback, boolean prune, boolean pruneDelete) {
        return new LlmRecordReplayService(
                tempDir.toString(), replay, record, fallback, prune, pruneDelete);
    }

    private LlmRecordReplayService replayOnly() {
        return service(true, false, "error", false, false);
    }

    private LlmRecordReplayService recordOnly() {
        return service(false, true, "error", false, false);
    }

    private LlmRecordReplayService recordAndReplay() {
        return service(true, true, "live", false, false);
    }

    // ── Hash computation ──────────────────────────────────────────────────────

    @Nested
    class HashPrompt {

        @Test
        void samePromptProducesSameHash() {
            var svc = replayOnly();
            String h1 = svc.hashPrompt("Rate the following nodes");
            String h2 = svc.hashPrompt("Rate the following nodes");
            assertEquals(h1, h2);
        }

        @Test
        void hashStartsWithSha256Prefix() {
            var svc = replayOnly();
            assertTrue(svc.hashPrompt("hello").startsWith("sha256-"));
        }

        @Test
        void whitespaceNormalisationProducesSameHash() {
            var svc = replayOnly();
            String h1 = svc.hashPrompt("Rate  the\n  following   nodes");
            String h2 = svc.hashPrompt("Rate the following nodes");
            assertEquals(h1, h2, "Whitespace normalisation should produce identical hashes");
        }

        @Test
        void differentPromptsProduceDifferentHashes() {
            var svc = replayOnly();
            String h1 = svc.hashPrompt("prompt A");
            String h2 = svc.hashPrompt("prompt B");
            assertNotEquals(h1, h2);
        }
    }

    // ── Record & Replay ───────────────────────────────────────────────────────

    @Nested
    class RecordAndReplay {

        @Test
        void recordCreatesFileAndReplayReturnsIt() {
            var recorder = recordOnly();
            recorder.record("my prompt", "{\"body\":true}", "GEMINI", "MyTest#test1");

            // Verify file was created
            String hash = recorder.hashPrompt("my prompt");
            assertTrue(Files.exists(tempDir.resolve(hash + ".json")));

            // Replay should find it
            var replayer = replayOnly();
            Optional<String> result = replayer.replay("my prompt");
            assertTrue(result.isPresent());
            assertEquals("{\"body\":true}", result.get());
        }

        @Test
        void replayReturnsEmptyWhenNoRecording() {
            var svc = replayOnly();
            Optional<String> result = svc.replay("unknown prompt");
            assertTrue(result.isEmpty());
        }

        @Test
        void recordOverwritesExistingRecording() {
            var recorder = recordOnly();
            recorder.record("prompt", "response-v1", "GEMINI", null);
            recorder.record("prompt", "response-v2", "GEMINI", null);

            var replayer = replayOnly();
            Optional<String> result = replayer.replay("prompt");
            assertTrue(result.isPresent());
            assertEquals("response-v2", result.get());
        }
    }

    // ── Manifest ──────────────────────────────────────────────────────────────

    @Nested
    class Manifest {

        @Test
        void recordCreatesManifest() {
            var recorder = recordOnly();
            recorder.record("prompt", "response", "GEMINI", null);

            Path manifest = tempDir.resolve("manifest.json");
            assertTrue(Files.exists(manifest));
        }

        @Test
        void manifestContainsRecordedHash() throws IOException {
            var recorder = recordOnly();
            recorder.record("prompt", "response", "GEMINI", null);

            String hash = recorder.hashPrompt("prompt");
            String content = Files.readString(tempDir.resolve("manifest.json"));
            assertTrue(content.contains(hash));
        }
    }

    // ── Pruning ───────────────────────────────────────────────────────────────

    @Nested
    class Pruning {

        @Test
        void pruneMarksUnusedAsStale() throws IOException {
            // Record two prompts
            var recorder = recordOnly();
            recorder.record("prompt-a", "response-a", "GEMINI", null);
            recorder.record("prompt-b", "response-b", "GEMINI", null);

            // Replay only prompt-a, then prune
            var pruner = service(true, false, "error", true, false);
            pruner.replay("prompt-a");
            pruner.pruneUnused();

            String manifest = Files.readString(tempDir.resolve("manifest.json"));
            // prompt-b's entry should be marked stale
            assertTrue(manifest.contains("\"stale\" : true") || manifest.contains("\"stale\":true"),
                    "Unused recording should be marked stale in manifest");
        }

        @Test
        void pruneDeleteRemovesStaleFiles() {
            // Record two prompts
            var recorder = recordOnly();
            recorder.record("prompt-a", "response-a", "GEMINI", null);
            recorder.record("prompt-b", "response-b", "GEMINI", null);

            String hashB = recorder.hashPrompt("prompt-b");

            // Replay only prompt-a, then prune with delete
            var pruner = service(true, false, "error", true, true);
            pruner.replay("prompt-a");
            pruner.pruneUnused();

            // prompt-b's file should be deleted
            assertFalse(Files.exists(tempDir.resolve(hashB + ".json")),
                    "Stale recording file should be deleted");
        }
    }

    // ── Mode flags ────────────────────────────────────────────────────────────

    @Nested
    class ModeFlags {

        @Test
        void replayModeFlag() {
            var svc = replayOnly();
            assertTrue(svc.isReplayMode());
            assertFalse(svc.isRecordMode());
        }

        @Test
        void recordModeFlag() {
            var svc = recordOnly();
            assertFalse(svc.isReplayMode());
            assertTrue(svc.isRecordMode());
        }

        @Test
        void fallbackLive() {
            var svc = service(true, true, "live", false, false);
            assertTrue(svc.isFallbackLive());
        }

        @Test
        void fallbackError() {
            var svc = service(true, false, "error", false, false);
            assertFalse(svc.isFallbackLive());
        }

        @Test
        void defaultDisabled() {
            var svc = service(false, false, "error", false, false);
            assertFalse(svc.isReplayMode());
            assertFalse(svc.isRecordMode());
        }
    }
}
