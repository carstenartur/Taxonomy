package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Authored artifact bytes exercise resolution, not any external product's behavior. */
class PinnedArtifactResolverTest {
    @TempDir Path root;
    private static final byte[] BYTES = "independently pinned product fixture\n".getBytes(StandardCharsets.UTF_8);

    @Test void validLocalArtifactSeedsAnOfflineReusableCacheAndRecordsActualProvenance() throws Exception {
        Path manifest = manifest("Archi", "5.11.0", 1024);
        Path local = root.resolve("provided.tgz"); Files.write(local, BYTES);
        Result first = run(manifest, "--local", local.toString(), "--offline", "true");
        assertEquals(0, first.exit(), first.error());
        var receipt = FlatJson.parseObject(first.output());
        assertEquals("LOCAL", receipt.get("resolvedFrom"));
        assertEquals("Archi", receipt.get("producer"));
        assertEquals("5.11.0", receipt.get("version"));
        assertEquals(hash(), receipt.get("sha256"));
        assertEquals("https://example.invalid/pinned-product.tgz", receipt.get("source"));
        Path cached = Path.of((String) receipt.get("path"));
        assertArrayEquals(BYTES, Files.readAllBytes(cached));
        Files.delete(local);
        Result second = run(manifest, "--offline", "true");
        assertEquals(0, second.exit(), second.error());
        assertEquals("CACHE", FlatJson.parseObject(second.output()).get("resolvedFrom"));
        assertEquals(cached.toString(), FlatJson.parseObject(second.output()).get("path"));
    }

    @Test void corruptedCacheIsNeverReportedAsAvailableOffline() throws Exception {
        Path manifest = manifest("Archi", "5.11.0", 1024);
        Path local = root.resolve("provided.tgz"); Files.write(local, BYTES);
        Result seeded = run(manifest, "--local", local.toString());
        assertEquals(0, seeded.exit(), seeded.error());
        Path cached = Path.of((String) FlatJson.parseObject(seeded.output()).get("path"));
        Files.writeString(cached, "corrupted");
        Result result = run(manifest, "--offline", "true");
        assertEquals(1, result.exit());
        assertTrue(result.error().contains("SHA-256"), result.error());
        assertTrue(result.output().isEmpty());
    }

    @Test void wrongExplicitLocalInputDoesNotSilentlyFallBackToCachedBytes() throws Exception {
        Path manifest = manifest("Archi", "5.11.0", 1024);
        Path local = root.resolve("provided.tgz"); Files.write(local, BYTES);
        assertEquals(0, run(manifest, "--local", local.toString()).exit());
        Files.writeString(local, "a different producer");
        Result result = run(manifest, "--local", local.toString(), "--offline", "true");
        assertEquals(1, result.exit());
        assertTrue(result.error().contains("SHA-256"), result.error());
        assertTrue(result.output().isEmpty());
    }

    @Test void wrongProducerIsRejectedBeforeResolution() throws Exception {
        Result result = run(manifest("AnotherProduct", "5.11.0", 1024), "--offline", "true");
        assertEquals(1, result.exit());
        assertTrue(result.error().contains("producer"), result.error());
        assertFalse(Files.exists(root.resolve("cache")));
    }

    @Test void offlineMissingArtifactReportsMissingEvidenceInsteadOfDownloading() throws Exception {
        Result result = run(manifest("Archi", "5.11.0", 1024), "--offline", "true");
        assertEquals(1, result.exit());
        assertTrue(result.error().contains("preseed"), result.error());
        assertTrue(result.output().isEmpty());
    }

    @Test void oversizedLocalArtifactIsRejectedWithoutPublishingCacheEntry() throws Exception {
        Path local = root.resolve("provided.tgz"); Files.write(local, BYTES);
        Result result = run(manifest("Archi", "5.11.0", 4), "--local", local.toString());
        assertEquals(1, result.exit());
        assertTrue(result.error().contains("byte limit"), result.error());
        if (Files.exists(root.resolve("cache"))) try (var files = Files.list(root.resolve("cache"))) {
            assertEquals(0, files.count());
        }
    }

    @Test void interruptedDownloadCannotPublishPartialBytesOrLeaveTemporaryFiles() throws Exception {
        var artifact = new PinnedArtifactResolver.Artifact("archi-linux-x64", "Archi", "5.11.0",
                URI.create("https://example.invalid/pinned-product.tgz"), hash(), 1024);
        IOException failure = assertThrows(IOException.class, () -> PinnedArtifactResolver.resolve(artifact,
                root.resolve("cache"), null, false, (selected, target) -> {
                    Files.writeString(target, "partial");
                    throw new IOException("upstream disconnected");
                }));
        assertEquals("upstream disconnected", failure.getMessage());
        try (var files = Files.list(root.resolve("cache"))) { assertEquals(0, files.count()); }
    }

    @Test void validDownloadBecomesReusableEvenWhenUpstreamLaterDisappears() throws Exception {
        var artifact = new PinnedArtifactResolver.Artifact("archi-linux-x64", "Archi", "5.11.0",
                URI.create("https://example.invalid/pinned-product.tgz"), hash(), 1024);
        var downloaded = PinnedArtifactResolver.resolve(artifact, root.resolve("cache"), null, false,
                (selected, target) -> Files.write(target, BYTES));
        assertEquals("DOWNLOAD", downloaded.get("resolvedFrom"));
        var cached = PinnedArtifactResolver.resolve(artifact, root.resolve("cache"), null, false,
                (selected, target) -> { throw new IOException("upstream HTTP 404"); });
        assertEquals("CACHE", cached.get("resolvedFrom"));
        assertArrayEquals(BYTES, Files.readAllBytes(Path.of((String) cached.get("path"))));
    }

    @Test void wrongDownloadDigestNeverBecomesAUsableCacheEntry() throws Exception {
        var artifact = new PinnedArtifactResolver.Artifact("archi-linux-x64", "Archi", "5.11.0",
                URI.create("https://example.invalid/pinned-product.tgz"), hash(), 1024);
        assertThrows(IOException.class, () -> PinnedArtifactResolver.resolve(artifact, root.resolve("cache"), null, false,
                (selected, target) -> Files.writeString(target, "wrong release")));
        try (var files = Files.list(root.resolve("cache"))) { assertEquals(0, files.count()); }
    }

    @Test void manifestSchemaAndByteLimitMustBeExactIntegers() throws Exception {
        for (String property : List.of("schemaVersion", "maxBytes")) {
            Path manifest = manifest("Archi", "5.11.0", 1024);
            String content = Files.readString(manifest).replace(
                    "\"" + property + "\": " + (property.equals("schemaVersion") ? "1" : "1024"),
                    "\"" + property + "\": " + (property.equals("schemaVersion") ? "1.5" : "1024.5"));
            Files.writeString(manifest, content);
            assertThrows(IllegalArgumentException.class,
                    () -> PinnedArtifactResolver.readManifest(manifest, "archi-linux-x64", "Archi"), property);
        }
    }

    private Path manifest(String producer, String version, int maxBytes) throws Exception {
        Path path = root.resolve("products.json");
        Files.writeString(path, FlatJson.pretty(Map.of("schemaVersion", 1, "artifacts", List.of(Map.of(
                "id", "archi-linux-x64", "producer", producer, "version", version,
                "source", "https://example.invalid/pinned-product.tgz", "sha256", hash(), "maxBytes", maxBytes)))));
        return path;
    }

    private Result run(Path manifest, String... extra) {
        var args = new ArrayList<>(List.of("resolve-product-artifact", "--manifest", manifest.toString(),
                "--artifact", "archi-linux-x64", "--producer", "Archi", "--cache", root.resolve("cache").toString()));
        args.addAll(List.of(extra));
        var output = new ByteArrayOutputStream(); var error = new ByteArrayOutputStream();
        int exit = TaxonomyTooling.run(args.toArray(String[]::new), root, new PrintStream(output), new PrintStream(error));
        return new Result(exit, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private static String hash() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES));
    }
    private record Result(int exit, String output, String error) { }
}
