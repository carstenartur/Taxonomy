package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Executes provisioning offline; only HTTP is replaced, SHA-256 and installation are real. */
class EmbeddingModelProvisioningTest {
    private static final List<String> FILES = List.of("model.onnx", "tokenizer.json",
            "tokenizer_config.json", "special_tokens_map.json", "config.json");
    private static final List<String> MULTILINGUAL_DIGESTS = List.of(
            "98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f",
            "2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8",
            "5036ea374ffedd706e3bef33e2e0d6953cb868ef8a490e76e32ba0faa37a6b9b",
            "378eb3bf733eb16e65792d7e3fda5b8a4631387ca04d2015199c4d4f22ae554d",
            "6300193cb75e01cf80c96decef7187dfb33094d97cc1490b7ead6ff134476e4e");
    private static final List<String> LEGACY_DIGESTS = List.of(
            "828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35",
            "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66",
            "9261e7d79b44c8195c1cada2b453e55b00aeb81e907a6664974b4d7776172ab3",
            "b6d346be366a7d1d48332dbc9fdf3bf8960b5d879522b7799ddba59e76237ee3",
            "094f8e891b932f2000c92cfc663bac4c62069f5d8af5b5278c4306aef3084750");
    private static final String MULTILINGUAL_BASE = "https://huggingface.co/sentence-transformers/"
            + "paraphrase-multilingual-MiniLM-L12-v2/resolve/e8f8c211226b894fcb81acc59f3b34ba3efd5f42/";
    private static final String LEGACY_BASE = "https://huggingface.co/BAAI/bge-small-en-v1.5/"
            + "resolve/5c38ec7c405ec4b44b94cc5a9bb96e735b38267a/";

    @Test void defaultProvisionsPinnedMultilingualExport(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        Result result = run(fixture, Map.of());
        assertEquals(0, result.exitCode(), result.output());
        Path model = temp.resolve("models/multilingual-minilm");
        assertModel(model);
        assertEquals(expectedUrls(MULTILINGUAL_BASE, "onnx/model_quint8_avx2.onnx"), urls(fixture));
        String provenance = Files.readString(model.resolve("MODEL_PROVENANCE.txt"));
        assertTrue(provenance.contains("profile=MULTILINGUAL_MINILM_L12"), provenance);
        assertTrue(provenance.contains("license=Apache-2.0"), provenance);
    }

    @Test void explicitLegacyProfileRetainsItsOwnExportAndLicense(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        Result result = run(fixture, Map.of("MODEL_PROFILE", "BGE_SMALL_EN"));
        assertEquals(0, result.exitCode(), result.output());
        Path model = temp.resolve("models/bge-small-en-v1.5");
        assertModel(model);
        assertEquals(expectedUrls(LEGACY_BASE, "onnx/model.onnx"), urls(fixture));
        assertTrue(Files.readString(model.resolve("MODEL_PROVENANCE.txt")).contains("license=MIT"));
    }

    @Test void tokenAuthenticatesEveryDownloadWithoutEnteringLogsOrArtifacts(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        String token = "offline-fixture-" + java.util.UUID.randomUUID();
        Result result = run(fixture, Map.of("HF_TOKEN", token));
        assertEquals(0, result.exitCode(), "Authenticated offline provisioning must succeed");
        assertEquals(expectedUrls(MULTILINGUAL_BASE, "onnx/model_quint8_avx2.onnx"), urls(fixture));
        assertFalse(result.output().contains(token), "Provisioning output must not expose the token");
        assertFalse(Files.readString(fixture.log()).contains(token), "Request logs must not expose the token");
        Path model = temp.resolve("models/multilingual-minilm");
        assertModel(model);
        try (var artifacts = Files.list(model)) {
            for (Path artifact : artifacts.toList()) {
                assertFalse(Files.readString(artifact).contains(token), "Model artifacts must not expose the token");
            }
        }
    }

    @Test void customDirectoryDoesNotChangeTheSelectedProfile(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        Path directory = temp.resolve("models/bge-small-en-v1.5");
        Result result = run(fixture, Map.of("MODEL_DIRECTORY", directory.toString()));
        assertEquals(0, result.exitCode(), result.output());
        assertModel(directory);
        assertEquals(expectedUrls(MULTILINGUAL_BASE, "onnx/model_quint8_avx2.onnx"), urls(fixture));
    }

    @Test void validCacheSkipsHttpButEveryCorruptFileRequiresRepair(@TempDir Path temp) throws Exception {
        for (String profile : List.of("MULTILINGUAL_MINILM_L12", "BGE_SMALL_EN")) {
            Fixture fixture = fixture(temp.resolve(profile));
            Path directory = fixture.root().resolve("cache");
            Map<String, String> env = Map.of("MODEL_PROFILE", profile, "MODEL_DIRECTORY", directory.toString());
            assertEquals(0, run(fixture, env).exitCode());
            Files.delete(fixture.log());
            assertEquals(0, run(fixture, env).exitCode());
            assertFalse(Files.exists(fixture.log()), "Valid cache must not call HTTP");
            for (String file : FILES) {
                Files.writeString(directory.resolve(file), "nonempty corrupt data");
                Result repaired = run(fixture, env);
                assertEquals(0, repaired.exitCode(), repaired.output());
                assertEquals(5, urls(fixture).size(), "Corruption of " + file + " must invalidate cache");
                assertModel(directory);
                Files.delete(fixture.log());
            }
        }
    }

    @Test void corruptDownloadCannotReplaceExistingCacheForEitherProfile(@TempDir Path temp) throws Exception {
        for (String profile : List.of("MULTILINGUAL_MINILM_L12", "BGE_SMALL_EN")) {
            for (String file : FILES) {
                Fixture fixture = fixture(temp.resolve(profile).resolve(file));
                Path directory = Files.createDirectories(fixture.root().resolve("cache"));
                Files.writeString(directory.resolve("model.onnx"), "previous model");
                Files.writeString(directory.resolve("MODEL_PROVENANCE.txt"), "previous provenance");
                Result result = run(fixture, Map.of("MODEL_PROFILE", profile,
                        "MODEL_DIRECTORY", directory.toString(), "CORRUPT_FILE", file));
                assertNotEquals(0, result.exitCode(), "Must reject " + profile + "/" + file);
                assertEquals("previous model", Files.readString(directory.resolve("model.onnx")));
                assertEquals("previous provenance", Files.readString(directory.resolve("MODEL_PROVENANCE.txt")));
                try (var paths = Files.list(directory)) {
                    assertEquals(List.of("MODEL_PROVENANCE.txt", "model.onnx"),
                            paths.map(path -> path.getFileName().toString()).sorted().toList());
                }
                try (var paths = Files.list(fixture.root())) {
                    assertFalse(paths.anyMatch(path -> path.getFileName().toString().contains(".download.")));
                }
            }
        }
    }

    @Test void unknownProfileAndMismatchedOverridesFailBeforeHttp(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        for (Map<String, String> env : List.of(Map.of("MODEL_PROFILE", "UNKNOWN"),
                Map.of("MODEL_REVISION", "floating-main"), Map.of("MODEL_REPOSITORY", "different/model"),
                Map.of("MODEL_ONNX_SHA256", "0".repeat(64)))) {
            Result result = run(fixture, env);
            assertNotEquals(0, result.exitCode(), "Must reject unpinned override " + env.keySet());
            assertFalse(Files.exists(fixture.log()), "Must reject before HTTP");
        }
    }

    private static Fixture fixture(Path root) throws Exception {
        Path bin = Files.createDirectories(root.resolve("bin"));
        String script = Files.readString(repositoryRoot().resolve(".github/scripts/download-embedding-model.sh"));
        // Use tiny independent fixtures instead of downloading hundreds of MB. The trust roots alone
        // are substituted; profile selection, URLs, real sha256sum, cache checks and install run unchanged.
        for (int i = 0; i < FILES.size(); i++) {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload(FILES.get(i)).getBytes(StandardCharsets.UTF_8)));
            script = script.replace(MULTILINGUAL_DIGESTS.get(i), digest).replace(LEGACY_DIGESTS.get(i), digest);
        }
        Path helper = root.resolve("download.sh");
        Files.writeString(helper, script);
        Path curl = bin.resolve("curl");
        Files.writeString(curl, """
                #!/usr/bin/env bash
                set -euo pipefail
                args=("$@")
                url=${args[${#args[@]}-1]}
                printf '%s\\n' "$url" >> "$CURL_LOG"
                target=''
                authorization=''
                while (($#)); do
                  case "$1" in
                    --output) target=$2; shift 2 ;;
                    --header)
                      if [[ "$2" == Authorization:* ]]; then authorization=$2; fi
                      shift 2 ;;
                    *) shift ;;
                  esac
                done
                if [[ -n "${HF_TOKEN:-}" ]]; then
                  [[ "$authorization" == "Authorization: Bearer ${HF_TOKEN}" ]] || {
                    printf 'Authentication header mismatch\\n' >&2; exit 1;
                  }
                else
                  [[ -z "$authorization" ]] || {
                    printf 'Unexpected authentication header\\n' >&2; exit 1;
                  }
                fi
                [[ -n "$target" ]]
                name=${target##*/}
                if [[ "$name" == "${CORRUPT_FILE:-}" ]]; then
                  printf 'corrupt\\n' > "$target"
                else
                  printf 'fixture:%s\\n' "$name" > "$target"
                fi
                """);
        assertTrue(curl.toFile().setExecutable(true));
        return new Fixture(root, helper, bin, root.resolve("urls.log"));
    }

    private static Result run(Fixture fixture, Map<String, String> overrides) throws Exception {
        Path output = fixture.root().resolve("output.log");
        ProcessBuilder builder = new ProcessBuilder("bash", fixture.helper().toString())
                .directory(fixture.root().toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        Map<String, String> env = builder.environment();
        for (String name : List.of("MODEL_PROFILE", "MODEL_DIRECTORY", "MODEL_REPOSITORY", "MODEL_REVISION",
                "MODEL_ONNX_SHA256", "HF_TOKEN", "CORRUPT_FILE")) env.remove(name);
        env.put("PATH", fixture.bin() + ":" + env.get("PATH"));
        env.put("CURL_LOG", fixture.log().toString());
        env.putAll(overrides);
        Process process = builder.start();
        boolean completed = process.waitFor(20, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        assertTrue(completed, "Offline provisioning exceeded its bound");
        return new Result(process.exitValue(), Files.readString(output));
    }

    private static void assertModel(Path directory) throws Exception {
        for (String file : FILES) {
            assertTrue(Files.isRegularFile(directory.resolve(file)), "Must provision " + directory.resolve(file));
            assertEquals(payload(file), Files.readString(directory.resolve(file)), file);
        }
    }

    private static String payload(String file) { return "fixture:" + file + "\n"; }

    private static List<String> urls(Fixture fixture) throws Exception {
        assertTrue(Files.isRegularFile(fixture.log()), "Expected model HTTP requests");
        return Files.readAllLines(fixture.log());
    }

    private static List<String> expectedUrls(String base, String modelPath) {
        return List.of(base + modelPath + "?download=true", base + "tokenizer.json?download=true",
                base + "tokenizer_config.json?download=true", base + "special_tokens_map.json?download=true",
                base + "config.json?download=true");
    }

    private static Path repositoryRoot() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("taxonomy-app/pom.xml"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Taxonomy checkout not found");
        return path;
    }

    private record Fixture(Path root, Path helper, Path bin, Path log) { }
    private record Result(int exitCode, String output) { }
}
