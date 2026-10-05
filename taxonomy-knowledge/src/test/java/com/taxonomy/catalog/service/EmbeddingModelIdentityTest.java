package com.taxonomy.catalog.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingModelIdentityTest {
    @TempDir Path directory;

    @Test
    void identitiesFollowArtifactContentAndInferenceConfigurationRatherThanMachinePath() throws Exception {
        Path first = model("first");
        Path second = model("second");
        var identity = EmbeddingModelIdentity.capture(first, "query: ");
        assertThat(EmbeddingModelIdentity.capture(second, "query: ")).isEqualTo(identity);
        assertThat(EmbeddingModelIdentity.capture(second, "different: ")).isNotEqualTo(identity);
        Files.writeString(second.resolve("tokenizer.json"), "tokenizer-v2");
        assertThat(EmbeddingModelIdentity.capture(second, "query: ")).isNotEqualTo(identity);
        Files.writeString(second.resolve("tokenizer.json"), "tokenizer-v1");
        Files.writeString(second.resolve("config.json"), "config-v2");
        assertThat(EmbeddingModelIdentity.capture(second, "query: ")).isNotEqualTo(identity);
        Files.writeString(second.resolve("config.json"), "config-v1");
        Files.writeString(second.resolve("model.onnx"), "model-v2");
        assertThat(EmbeddingModelIdentity.capture(second, "query: ")).isNotEqualTo(identity);
        assertThat(identity.inferenceVersion()).contains("cls", "normalize", "384");
    }

    @Test
    void servingOptionsAndCompanionWeightsParticipateInTheFrozenConfiguration() throws Exception {
        Path path = model("configured");
        var original = EmbeddingModelIdentity.capture(path, "query: ");
        Files.writeString(path.resolve("serving.properties"), "option.maxLength=64");
        var configured = EmbeddingModelIdentity.capture(path, "query: ");
        assertThat(configured).isNotEqualTo(original);
        Path weights = Files.createDirectory(path.resolve("weights")).resolve("external.data");
        Files.writeString(weights, "weights-v1");
        var withWeights = EmbeddingModelIdentity.capture(path, "query: ");
        assertThat(withWeights).isNotEqualTo(configured);
        Files.writeString(weights, "weights-v2");
        assertThat(EmbeddingModelIdentity.capture(path, "query: ")).isNotEqualTo(withWeights);
    }

    @Test
    void missingModelOrTokenizerCannotBeAValidFrozenIdentity() throws Exception {
        Path missing = Files.createDirectory(directory.resolve("missing"));
        assertThatThrownBy(() -> EmbeddingModelIdentity.capture(missing, ""))
                .isInstanceOf(java.io.IOException.class);
        Files.writeString(missing.resolve("model.onnx"), "model-v1");
        assertThatThrownBy(() -> EmbeddingModelIdentity.capture(missing, ""))
                .isInstanceOf(java.io.IOException.class);
    }

    private Path model(String name) throws Exception {
        Path path = Files.createDirectory(directory.resolve(name));
        Files.writeString(path.resolve("model.onnx"), "model-v1");
        Files.writeString(path.resolve("tokenizer.json"), "tokenizer-v1");
        Files.writeString(path.resolve("config.json"), "config-v1");
        return path;
    }
}
