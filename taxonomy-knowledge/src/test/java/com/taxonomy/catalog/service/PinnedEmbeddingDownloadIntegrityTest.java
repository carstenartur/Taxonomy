package com.taxonomy.catalog.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/** Real downloader cache validation and SHA-256 against tiny independent offline trust roots. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class PinnedEmbeddingDownloadIntegrityTest {
    private static final String REPOSITORY = "https://huggingface.co/example/offline-fixture";
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    private static final List<String> FILES = List.of("model.onnx", "tokenizer.json",
            "tokenizer_config.json", "special_tokens_map.json", "config.json");

    @Test void corruptCachedTokenizerFailsBeforeAnyHttp(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            Fixture fixture = fixture(home);
            corruptBothCaches(fixture, "tokenizer.json");
            IOException failure = assertThrows(IOException.class, () -> download(fixture.service(), REPOSITORY));
            assertTrue(failure.getMessage().contains("tokenizer.json"), failure.getMessage());
            assertTrue(failure.getMessage().contains("SHA-256"), failure.getMessage());
            assertTrue(failure.getMessage().contains("re-provision"), failure.getMessage());
        });
    }

    @Test void corruptCachedModelFailsBeforeAnyHttp(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            Fixture fixture = fixture(home);
            corruptBothCaches(fixture, "model.onnx");
            IOException failure = assertThrows(IOException.class, () -> download(fixture.service(), REPOSITORY));
            assertTrue(failure.getMessage().contains("model.onnx"), failure.getMessage());
            assertTrue(failure.getMessage().contains("SHA-256"), failure.getMessage());
        });
    }

    @Test void corruptPinnedConfigurationFilesAreNeverAccepted(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            for (String file : FILES.subList(2, FILES.size())) {
                Fixture fixture = fixture(home);
                corruptBothCaches(fixture, file);
                IOException failure = assertThrows(IOException.class, () -> download(fixture.service(), REPOSITORY));
                assertTrue(failure.getMessage().contains(file), failure.getMessage());
            }
        });
    }

    @Test void validPinnedCacheUsesProfileAndImmutableRevision(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            Fixture fixture = fixture(home);
            assertEquals(fixture.pinnedCache().toString(), download(fixture.service(), REPOSITORY));
        });
    }

    @Test void customUrlCacheRetainsCallerSuppliedModelFlexibility(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            Fixture fixture = fixture(home);
            String custom = "https://huggingface.co/example/custom-export";
            Path customCache = cacheRoot(home).resolve("example--custom-export");
            Files.createDirectories(customCache);
            Files.writeString(customCache.resolve("model.onnx"), "custom model bytes");
            Files.writeString(customCache.resolve("tokenizer.json"), "custom tokenizer bytes");
            assertEquals(customCache.toString(), download(fixture.service(), custom));
            assertEquals("custom tokenizer bytes", Files.readString(customCache.resolve("tokenizer.json")));
        });
    }

    @Test void knownLegacyWeightsCannotLoadUnderMultilingualSemantics(@TempDir Path directory) throws Exception {
        LocalEmbeddingService service = new LocalEmbeddingService();
        var legacyIdentity = new EmbeddingModelIdentity(EmbeddingModelProfile.BGE_SMALL_EN.modelSha256(),
                "a".repeat(64), Map.of("config.json", "b".repeat(64)), "", "fixture");
        try (var identities = mockStatic(EmbeddingModelIdentity.class)) {
            identities.when(() -> EmbeddingModelIdentity.capture(directory, "",
                    EmbeddingModelProfile.MULTILINGUAL_MINILM_L12)).thenReturn(legacyIdentity);
            var method = LocalEmbeddingService.class.getDeclaredMethod("loadModel", String.class);
            method.setAccessible(true);
            InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                    () -> method.invoke(service, directory.toString()));
            assertInstanceOf(IllegalStateException.class, wrapped.getCause());
            assertTrue(wrapped.getCause().getMessage().contains("BGE_SMALL_EN"), wrapped.getCause().getMessage());
            assertTrue(wrapped.getCause().getMessage().contains("TAXONOMY_EMBEDDING_MODEL_PROFILE"),
                    wrapped.getCause().getMessage());
        }
    }

    @Test void stagedDownloadUsesImmutableExportAndInstallsAllVerifiedFiles(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            List<String> requests = new ArrayList<>();
            HttpServer server = fixtureServer(requests, null);
            try {
                String repository = "http://127.0.0.1:" + server.getAddress().getPort() + "/fixture";
                LocalEmbeddingService service = service(repository);
                Path cache = Path.of(download(service, repository));
                for (String file : FILES) assertEquals(payload(file), Files.readString(cache.resolve(file)));
                assertEquals(List.of("/resolve/" + REVISION + "/onnx/model_quint8_avx2.onnx",
                        "/resolve/" + REVISION + "/tokenizer.json",
                        "/resolve/" + REVISION + "/tokenizer_config.json",
                        "/resolve/" + REVISION + "/special_tokens_map.json",
                        "/resolve/" + REVISION + "/config.json"), requests);
                assertNoTemporaryDownloads(home);
            } finally { server.stop(0); }
        });
    }

    @Test void stagedChecksumFailurePreservesPreviousCacheAndCleansPartialFiles(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            List<String> requests = new ArrayList<>();
            HttpServer server = fixtureServer(requests, "tokenizer.json");
            try {
                String repository = "http://127.0.0.1:" + server.getAddress().getPort() + "/fixture";
                Path cache = cacheRoot(home).resolve("http:----127.0.0.1:" + server.getAddress().getPort()
                        + "--fixture/MULTILINGUAL_MINILM_L12/" + REVISION);
                Files.createDirectories(cache);
                Files.writeString(cache.resolve("model.onnx"), payload("model.onnx"));
                IOException failure = assertThrows(IOException.class, () -> download(service(repository), repository));
                assertTrue(failure.getMessage().contains("tokenizer.json"), failure.getMessage());
                assertEquals(4, requests.size(), "Each missing file is fetched once; checksums never trigger retries");
                assertEquals(payload("model.onnx"), Files.readString(cache.resolve("model.onnx")));
                try (var paths = Files.list(cache)) {
                    assertEquals(List.of("model.onnx"), paths.map(path -> path.getFileName().toString()).toList());
                }
                assertNoTemporaryDownloads(home);
            } finally { server.stop(0); }
        });
    }

    private static Fixture fixture(Path home) throws Exception {
        LocalEmbeddingService service = service(REPOSITORY);
        Path legacyCache = cacheRoot(home).resolve("example--offline-fixture");
        Path pinnedCache = legacyCache.resolve("MULTILINGUAL_MINILM_L12").resolve(REVISION);
        for (Path cache : List.of(legacyCache, pinnedCache)) {
            Files.createDirectories(cache);
            for (String file : FILES) Files.writeString(cache.resolve(file), payload(file));
        }
        return new Fixture(service, legacyCache, pinnedCache);
    }

    private static LocalEmbeddingService service(String repository) throws Exception {
        Map<String, String> digests = new LinkedHashMap<>();
        for (String file : FILES) digests.put(file, digest(payload(file)));
        // Metadata is the external trust boundary; keep cache logic, bytes and SHA-256 real.
        // The named answer also supports the new metadata method without requiring it to
        // exist during the initial red run against the previous implementation.
        EmbeddingModelProfile profile = mock(EmbeddingModelProfile.class, invocation ->
                switch (invocation.getMethod().getName()) {
                    case "modelUrl" -> repository;
                    case "revision" -> REVISION;
                    case "modelFile" -> "onnx/model_quint8_avx2.onnx";
                    case "modelSha256" -> digests.get("model.onnx");
                    case "fileSha256" -> Map.copyOf(digests);
                    case "name" -> "MULTILINGUAL_MINILM_L12";
                    case "queryPrefix", "documentPrefix" -> "";
                    case "pooling" -> "mean";
                    case "maxTokens" -> 128;
                    default -> null;
                });
        LocalEmbeddingService service = new LocalEmbeddingService();
        ReflectionTestUtils.setField(service, "modelProfile", profile);
        return service;
    }

    private static HttpServer fixtureServer(List<String> requests, String corruptFile) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixture", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/fixture".length());
            requests.add(path);
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.equals("model_quint8_avx2.onnx")) name = "model.onnx";
            byte[] body = (name.equals(corruptFile) ? "corrupt bytes" : payload(name))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        return server;
    }

    private static void assertNoTemporaryDownloads(Path home) throws IOException {
        try (var paths = Files.walk(home)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().contains(".download.")));
        }
    }

    private static void corruptBothCaches(Fixture fixture, String file) throws Exception {
        for (Path cache : List.of(fixture.legacyCache(), fixture.pinnedCache())) {
            Files.writeString(cache.resolve(file), "nonempty corrupt fixture");
        }
    }

    private static Path cacheRoot(Path home) { return home.resolve(".djl.ai/cache/taxonomy"); }
    private static String payload(String file) { return "verified fixture:" + file + "\n"; }
    private static String digest(String payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String download(LocalEmbeddingService service, String url) throws Exception {
        var method = LocalEmbeddingService.class.getDeclaredMethod("downloadHuggingFaceModel", String.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(service, url);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    private static void withHome(Path home, ThrowingRunnable test) throws Exception {
        String previous = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try { test.run(); }
        finally {
            if (previous == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previous);
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }
    private record Fixture(LocalEmbeddingService service, Path legacyCache, Path pinnedCache) { }
}
