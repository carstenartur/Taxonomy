package com.taxonomy.composition.analysis.artemis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/** Offline guard and launcher checks; no broker, Spring context or model inference is started. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class WorkerRuntimeFootprintModelContractTest {
    private static final List<String> FILES = List.of("model.onnx", "tokenizer.json",
            "tokenizer_config.json", "special_tokens_map.json", "config.json");

    @Test void validFiveFileBundlePassesAndRecordsActualDigests(@TempDir Path directory) throws Exception {
        Map<String, String> trusted = fixture(directory);
        assertEquals(trusted, WorkerRuntimeFootprintHarness.verifyPinnedArtifacts(directory, trusted));
    }

    @Test void everyNonemptyCorruptArtifactFailsByName(@TempDir Path directory) throws Exception {
        for (String file : FILES) {
            Map<String, String> trusted = fixture(directory);
            Files.writeString(directory.resolve(file), "nonempty corruption");
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> WorkerRuntimeFootprintHarness.verifyPinnedArtifacts(directory, trusted));
            assertTrue(failure.getMessage().contains(file), failure.getMessage());
            assertTrue(failure.getMessage().contains("checksum"), failure.getMessage());
        }
    }

    @Test void missingTokenizerConfigurationFailsBeforeMeasurement(@TempDir Path directory) throws Exception {
        Map<String, String> trusted = fixture(directory);
        Files.delete(directory.resolve("tokenizer_config.json"));
        AssertionError failure = assertThrows(AssertionError.class,
                () -> WorkerRuntimeFootprintHarness.verifyPinnedArtifacts(directory, trusted));
        assertTrue(failure.getMessage().contains("tokenizer_config.json"), failure.getMessage());
    }

    @Test void probeExplicitlySelectsMultilingualSemanticsAndDisablesDownloads(@TempDir Path directory) throws Exception {
        AtomicReference<List<String>> captured = new AtomicReference<>();
        String previousPassword = System.getProperty("taxonomy.admin-password");
        try (var builders = mockConstruction(SpringApplicationBuilder.class, (builder, context) ->
                when(builder.run(any(String[].class))).thenAnswer(invocation -> {
                    captured.set(List.of((String[]) invocation.getRawArguments()[0]));
                    throw new StartupCaptured();
                }))) {
            assertThrows(StartupCaptured.class, () -> WorkerRuntimeFootprintProbe.main(new String[] {
                    "worker", "CP", "true", "tcp://127.0.0.1:1", directory.toString(), directory.toString() }));
            assertTrue(captured.get().contains("--embedding.model.profile=MULTILINGUAL_MINILM_L12"));
            assertTrue(captured.get().contains("--embedding.query.prefix="));
            assertTrue(captured.get().contains("--embedding.allow-download=false"));
        } finally {
            if (previousPassword == null) System.clearProperty("taxonomy.admin-password");
            else System.setProperty("taxonomy.admin-password", previousPassword);
        }
    }

    private static Map<String, String> fixture(Path directory) throws Exception {
        Map<String, String> trusted = new LinkedHashMap<>();
        for (String file : FILES) {
            byte[] payload = ("independent trusted fixture:" + file + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(directory.resolve(file), payload);
            trusted.put(file, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
        }
        return trusted;
    }

    private static final class StartupCaptured extends RuntimeException { }
}
