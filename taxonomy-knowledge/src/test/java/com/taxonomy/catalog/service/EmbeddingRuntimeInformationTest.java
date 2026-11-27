package com.taxonomy.catalog.service;

import ai.djl.repository.zoo.ZooModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

class EmbeddingRuntimeInformationTest {

    @TempDir Path directory;

    @Test
    void observingAnUnloadedOverrideDoesNotTouchTheModelDirectoryOrIndex() throws Exception {
        var service = configured(EmbeddingModelProfile.MULTILINGUAL_MINILM_L12);
        var index = mock(jakarta.persistence.EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", index);
        ReflectionTestUtils.setField(service, "modelDir", directory.toString());
        Files.writeString(directory.resolve("model.onnx"), "placeholder weights to detect any serving properties write");
        ReflectionTestUtils.setField(service, "modelName", "https://user:dummy@private.invalid/model?token=dummy");

        var snapshot = service.runtimeInformation();

        assertThat(snapshot.state().name()).isEqualTo("NOT_LOADED");
        assertThat(snapshot.configuredProfile().source().name()).isEqualTo("OPERATOR_OVERRIDE");
        assertThat(snapshot.configuredProfile().upstream().license()).isEqualTo("Apache-2.0");
        assertThat(snapshot.loadedArtifact()).isNull();
        assertThat(snapshot.toString()).doesNotContain(directory.toString(), "private.invalid", "dummy");
        assertThat(directory.resolve("serving.properties")).doesNotExist();
        verifyNoInteractions(index);
    }

    @Test
    void observingTheRemoteDefaultDoesNotAttemptItsUnavailableDownload() {
        var service = configured(EmbeddingModelProfile.MULTILINGUAL_MINILM_L12);

        assertThat(service.runtimeInformation().state().name()).isEqualTo("NOT_LOADED");
        assertThat(service.runtimeInformation().configuredProfile().source().name()).isEqualTo("PROFILE_DEFAULT");
        assertThat(service.isAvailable()).isTrue();

        assertThatThrownBy(service::getModel).hasMessageContaining("No local model and download disabled");
        assertThat(service.runtimeInformation().state().name()).isEqualTo("FAILED");
        assertThat(service.runtimeInformation().loadedArtifact()).isNull();
    }

    @Test
    void observingDisabledAndStoppedModelsReportsTheActualLifecycle() {
        var service = new LocalEmbeddingService();

        assertThat(service.runtimeInformation().state().name()).isEqualTo("DISABLED");
        service.closeModel();
        assertThat(service.runtimeInformation().state().name()).isEqualTo("STOPPED");
        assertThat(service.runtimeInformation().loadedArtifact()).isNull();
    }

    @Test
    void observingAnInProgressLoadDoesNotWaitForTheModel() throws Exception {
        var service = configured(EmbeddingModelProfile.MULTILINGUAL_MINILM_L12);
        ReflectionTestUtils.setField(service, "modelDir", directory.toString());
        var loading = new CountDownLatch(1);
        var finishLoad = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            var load = executor.submit(() -> {
                try (var identities = mockStatic(EmbeddingModelIdentity.class)) {
                    identities.when(() -> EmbeddingModelIdentity.capture(
                            directory, "", EmbeddingModelProfile.MULTILINGUAL_MINILM_L12)).thenAnswer(invocation -> {
                        loading.countDown();
                        if (!finishLoad.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture load was not released");
                        throw new IOException("private artifact error");
                    });
                    assertThatThrownBy(service::getModel).isInstanceOf(IOException.class);
                }
            });
            assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();

            var snapshot = executor.submit(service::runtimeInformation).get(2, TimeUnit.SECONDS);
            assertThat(snapshot.state().name()).isEqualTo("LOADING");
            assertThat(snapshot.loadedArtifact()).isNull();
            finishLoad.countDown();
            load.get(5, TimeUnit.SECONDS);
            assertThat(service.runtimeInformation().state().name()).isEqualTo("FAILED");
            assertThat(service.runtimeInformation().toString()).doesNotContain("private artifact error");
        } finally {
            finishLoad.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(EmbeddingModelProfile.class)
    void onlyTheCompletePinnedBundleVerifiesUpstreamProvenance(EmbeddingModelProfile profile) {
        var service = configured(profile);
        ReflectionTestUtils.setField(service, "modelDir", "/operator-installed/embeddings");
        var hashes = new LinkedHashMap<>(profile.fileSha256());
        String modelHash = hashes.remove("model.onnx");
        String tokenizerHash = hashes.remove("tokenizer.json");
        hashes.put("serving.properties", "73be199e13f3d75152bf3aa93506c5d3a93b46217ebc360bf6179237d9848d11");
        hashes.put("MODEL_PROVENANCE.txt", "2".repeat(64));
        var identity = new EmbeddingModelIdentity(modelHash, tokenizerHash, hashes, "not public", "retained-djl-onnx-identity");
        var model = mock(ZooModel.class);
        ReflectionTestUtils.setField(service, "model", model);
        ReflectionTestUtils.setField(service, "loadedModelIdentity", identity);

        var snapshot = service.runtimeInformation();

        assertThat(snapshot.state().name()).isEqualTo("READY");
        assertThat(snapshot.configuredProfile().source().name()).isEqualTo("OPERATOR_OVERRIDE");
        assertThat(snapshot.loadedArtifact().provenance().name()).isEqualTo("VERIFIED_UPSTREAM");
        var upstream = snapshot.loadedArtifact().upstream();
        assertThat(upstream.revision()).isEqualTo(profile.revision());
        assertThat(upstream.license()).isEqualTo(profile == EmbeddingModelProfile.BGE_SMALL_EN ? "MIT" : "Apache-2.0");
        assertThat(upstream.origin()).isEqualTo(profile.modelUrl());
        assertThat(snapshot.loadedArtifact().modelSha256()).isEqualTo(modelHash);
        assertThat(snapshot.loadedArtifact().tokenizerSha256()).isEqualTo(tokenizerHash);
        assertThat(snapshot.loadedArtifact().inferenceVersion()).isEqualTo("retained-djl-onnx-identity");
        assertThat(snapshot.loadedArtifact().inferenceIdentity()).isEqualTo(identity.indexKey());
        assertThat(snapshot.toString()).doesNotContain("/operator-installed", "not public");
        verifyNoInteractions(model);

        for (var altered : Map.of(
                "extra config", withEntry(hashes, "extra.properties", "3".repeat(64)),
                "changed tokenizer options", withEntry(hashes, "tokenizer_config.json", "4".repeat(64)),
                "custom serving options", withEntry(hashes, "serving.properties", "5".repeat(64)),
                "missing configs", Map.<String, String>of()).entrySet()) {
            ReflectionTestUtils.setField(service, "loadedModelIdentity",
                    new EmbeddingModelIdentity(modelHash, tokenizerHash, altered.getValue(), "", "custom-runtime"));
            var custom = service.runtimeInformation().loadedArtifact();
            assertThat(custom.provenance().name()).as(altered.getKey()).isEqualTo("CUSTOM_OR_UNKNOWN");
            assertThat(custom.upstream()).isNull();
            assertThat(custom.modelSha256()).isEqualTo(modelHash);
        }

        service.closeModel();
        assertThat(service.runtimeInformation().loadedArtifact()).isNull();
    }

    private static Map<String, String> withEntry(Map<String, String> hashes, String name, String hash) {
        var result = new LinkedHashMap<>(hashes);
        result.put(name, hash);
        return result;
    }

    private static LocalEmbeddingService configured(EmbeddingModelProfile profile) {
        var service = new LocalEmbeddingService();
        ReflectionTestUtils.setField(service, "embeddingEnabled", true);
        ReflectionTestUtils.setField(service, "modelProfile", profile);
        return service;
    }
}
