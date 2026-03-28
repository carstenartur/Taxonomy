package com.taxonomy.analysis.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Records and replays raw LLM HTTP responses so that integration tests can run
 * without a real API key once the recordings exist.
 *
 * <h3>Modes (controlled via system properties / Spring properties)</h3>
 * <ul>
 *   <li>{@code llm.replay=true} — attempt to replay a previously recorded
 *       response for each prompt.  If no recording exists the behaviour depends
 *       on {@code llm.replay.fallback}.</li>
 *   <li>{@code llm.record=true} — after a real API call, persist the
 *       prompt + response so it can be replayed later.</li>
 *   <li>{@code llm.replay.fallback=live} — when replaying and no recording
 *       is found, fall through to the real API call <em>and</em> record the
 *       result.</li>
 *   <li>{@code llm.prune=true} — after the test run, mark recordings that
 *       were never replayed as <em>stale</em>.</li>
 *   <li>{@code llm.prune.delete=true} — delete stale recordings.</li>
 * </ul>
 *
 * <h3>Storage layout</h3>
 * <pre>
 *   src/test/resources/llm-recordings/
 *     manifest.json          — index of all recordings
 *     sha256-&lt;hex&gt;.json — individual recording files
 * </pre>
 */
@Service
public class LlmRecordReplayService {

    private static final Logger log = LoggerFactory.getLogger(LlmRecordReplayService.class);

    private final Path recordingsDir;
    private final ObjectMapper mapper;

    private final boolean replayMode;
    private final boolean recordMode;
    private final String  replayFallback;
    private final boolean pruneMode;
    private final boolean pruneDeleteMode;

    /** Hashes replayed during this JVM run — used for pruning. */
    private final Set<String> replayedHashes = ConcurrentHashMap.newKeySet();

    public LlmRecordReplayService(
            @Value("${llm.recordings.dir:#{null}}") String configuredDir,
            @Value("${llm.replay:false}") boolean replayMode,
            @Value("${llm.record:false}") boolean recordMode,
            @Value("${llm.replay.fallback:error}") String replayFallback,
            @Value("${llm.prune:false}") boolean pruneMode,
            @Value("${llm.prune.delete:false}") boolean pruneDeleteMode) {

        this.replayMode = replayMode;
        this.recordMode = recordMode;
        this.replayFallback = replayFallback;
        this.pruneMode = pruneMode;
        this.pruneDeleteMode = pruneDeleteMode;

        // Determine recordings directory
        if (configuredDir != null && !configuredDir.isBlank()) {
            this.recordingsDir = Path.of(configuredDir);
        } else {
            this.recordingsDir = detectRecordingsDir();
        }

        this.mapper = JsonMapper.builder().build();

        if (replayMode || recordMode) {
            log.info("LLM Record/Replay — replay={}, record={}, fallback={}, prune={}, prune.delete={}, dir={}",
                    replayMode, recordMode, replayFallback, pruneMode, pruneDeleteMode, recordingsDir);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean isReplayMode()  { return replayMode; }
    public boolean isRecordMode()  { return recordMode; }

    /**
     * Returns {@code true} when replay mode is active and a missing recording
     * should fall through to a live API call (and be recorded).
     */
    public boolean isFallbackLive() {
        return "live".equalsIgnoreCase(replayFallback);
    }

    /**
     * Attempts to replay a recorded response for the given prompt.
     *
     * @param prompt the LLM prompt text
     * @return the raw HTTP response body if a recording exists, otherwise empty
     */
    public Optional<String> replay(String prompt) {
        String hash = hashPrompt(prompt);
        Path file = recordingsDir.resolve(hash + ".json");

        if (!Files.exists(file)) {
            return Optional.empty();
        }

        try {
            LlmRecordingEntry entry = mapper.readValue(file.toFile(), LlmRecordingEntry.class);
            replayedHashes.add(hash);

            // Update lastUsed in manifest (best-effort)
            updateManifestLastUsed(hash);

            log.info("LLM REPLAY — returning recorded response for hash {} (recorded at {})",
                    hash, entry.recordedAt());
            return Optional.of(entry.responseBody());
        } catch (Exception e) {
            log.warn("Failed to read LLM recording {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Records a prompt + raw response so it can be replayed later.
     *
     * @param prompt       the LLM prompt text
     * @param rawResponse  the raw HTTP response body
     * @param provider     the provider name (e.g. "GEMINI")
     * @param testOrigin   optional test class#method that triggered the call
     */
    public void record(String prompt, String rawResponse, String provider, String testOrigin) {
        String hash = hashPrompt(prompt);
        String now = Instant.now().toString();

        LlmRecordingEntry entry = new LlmRecordingEntry(
                hash, prompt, rawResponse, provider, now, testOrigin, now);

        try {
            Files.createDirectories(recordingsDir);
            Path file = recordingsDir.resolve(hash + ".json");
            mapper.writeValue(file.toFile(), entry);
            log.info("LLM RECORD — saved recording {} for provider {} ({})",
                    hash, provider, testOrigin);

            updateManifest(hash, now);
        } catch (IOException e) {
            log.error("Failed to write LLM recording for hash {}: {}", hash, e.getMessage(), e);
        }
    }

    /**
     * Marks recordings that were never replayed in this JVM run as stale.
     * If {@code llm.prune.delete=true}, stale recordings are deleted.
     */
    public void pruneUnused() {
        if (!pruneMode && !pruneDeleteMode) return;

        Path manifestFile = recordingsDir.resolve("manifest.json");
        if (!Files.exists(manifestFile)) return;

        try {
            ManifestData manifest = mapper.readValue(manifestFile.toFile(), ManifestData.class);
            int staleCount = 0;
            List<String> toDelete = new ArrayList<>();

            for (Map.Entry<String, ManifestEntry> e : manifest.recordings.entrySet()) {
                if (!replayedHashes.contains(e.getKey())) {
                    e.getValue().stale = true;
                    staleCount++;
                    if (pruneDeleteMode) {
                        toDelete.add(e.getKey());
                    }
                }
            }

            // Delete stale files
            for (String hash : toDelete) {
                Path file = recordingsDir.resolve(hash + ".json");
                Files.deleteIfExists(file);
                manifest.recordings.remove(hash);
                log.info("LLM PRUNE — deleted stale recording {}", hash);
            }

            mapper.writeValue(manifestFile.toFile(), manifest);
            log.info("LLM PRUNE — {} stale recordings{}", staleCount,
                    pruneDeleteMode ? " (deleted)" : " (marked)");
        } catch (Exception e) {
            log.error("Failed to prune LLM recordings: {}", e.getMessage(), e);
        }
    }

    // ── Hash computation ──────────────────────────────────────────────────────

    /**
     * Computes a SHA-256 hash of the whitespace-normalised prompt text.
     * Normalisation collapses all whitespace runs to a single space and trims,
     * so minor formatting changes do not invalidate existing recordings.
     */
    String hashPrompt(String prompt) {
        String normalised = prompt.strip().replaceAll("\\s+", " ");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(normalised.getBytes(StandardCharsets.UTF_8));
            return "sha256-" + HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── Manifest management ───────────────────────────────────────────────────

    private void updateManifest(String hash, String now) {
        Path manifestFile = recordingsDir.resolve("manifest.json");
        ManifestData manifest;
        try {
            if (Files.exists(manifestFile)) {
                manifest = mapper.readValue(manifestFile.toFile(), ManifestData.class);
            } else {
                manifest = new ManifestData();
            }

            ManifestEntry entry = new ManifestEntry();
            entry.file = hash + ".json";
            entry.lastUsed = now;
            entry.stale = false;
            manifest.recordings.put(hash, entry);

            mapper.writeValue(manifestFile.toFile(), manifest);
        } catch (Exception e) {
            log.warn("Failed to update manifest: {}", e.getMessage());
        }
    }

    private void updateManifestLastUsed(String hash) {
        Path manifestFile = recordingsDir.resolve("manifest.json");
        if (!Files.exists(manifestFile)) return;

        try {
            ManifestData manifest = mapper.readValue(manifestFile.toFile(), ManifestData.class);
            ManifestEntry entry = manifest.recordings.get(hash);
            if (entry != null) {
                entry.lastUsed = Instant.now().toString();
                entry.stale = false;
                mapper.writeValue(manifestFile.toFile(), manifest);
            }
        } catch (Exception e) {
            log.warn("Failed to update manifest lastUsed for {}: {}", hash, e.getMessage());
        }
    }

    /**
     * Detects the recordings directory by looking for the standard location
     * relative to the project root.
     */
    private static Path detectRecordingsDir() {
        // Try src/test/resources/llm-recordings relative to CWD first,
        // then walk up to find a Maven module root.
        Path cwd = Path.of("").toAbsolutePath();

        // Walk up looking for a pom.xml with src/test/resources
        Path candidate = cwd;
        for (int i = 0; i < 5; i++) {
            Path testResources = candidate.resolve("src/test/resources/llm-recordings");
            if (Files.exists(candidate.resolve("pom.xml"))
                    && Files.exists(candidate.resolve("src/test/resources"))) {
                return testResources;
            }
            // Check if this is a multi-module project — look for a module with src/test/resources
            try (Stream<Path> children = Files.list(candidate)) {
                Optional<Path> moduleDir = children
                        .filter(Files::isDirectory)
                        .filter(d -> Files.exists(d.resolve("pom.xml")))
                        .filter(d -> Files.exists(d.resolve("src/test/resources")))
                        .findFirst();
                if (moduleDir.isPresent()) {
                    return moduleDir.get().resolve("src/test/resources/llm-recordings");
                }
            } catch (IOException ignored) { }
            candidate = candidate.getParent();
            if (candidate == null) break;
        }
        // Fallback
        return cwd.resolve("src/test/resources/llm-recordings");
    }

    // ── Manifest POJOs ────────────────────────────────────────────────────────

    static class ManifestData {
        public int version = 1;
        public Map<String, ManifestEntry> recordings = new LinkedHashMap<>();
    }

    static class ManifestEntry {
        public String file;
        public String lastUsed;
        public boolean stale;
    }
}
