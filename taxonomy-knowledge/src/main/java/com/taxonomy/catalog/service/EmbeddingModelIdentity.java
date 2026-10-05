package com.taxonomy.catalog.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Content identity of the actual loaded model, tokenizer and scoring configuration; paths are not identity. */
public record EmbeddingModelIdentity(String modelSha256, String tokenizerSha256,
                                     Map<String, String> configurationSha256, String queryPrefix,
                                     String inferenceVersion) {
    public static final String INFERENCE_VERSION = "djl-" + ai.djl.Model.class.getPackage().getSpecificationVersion()
            + ":tokenizer-" + ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory.class.getPackage().getSpecificationVersion()
            + ":onnx:cls:normalize:token-types:384:lucene-" + org.apache.lucene.util.Version.LATEST + ":cosine:v1";

    public EmbeddingModelIdentity {
        requireHash(modelSha256);
        requireHash(tokenizerSha256);
        configurationSha256 = Map.copyOf(configurationSha256);
        configurationSha256.values().forEach(EmbeddingModelIdentity::requireHash);
        queryPrefix = Objects.requireNonNull(queryPrefix, "queryPrefix");
        if (inferenceVersion == null || inferenceVersion.isBlank()) {
            throw new IllegalArgumentException("Embedding inference version is required");
        }
    }

    public static EmbeddingModelIdentity capture(Path directory, String queryPrefix) throws IOException {
        String model = hashFile(directory.resolve("model.onnx"), true);
        String tokenizer = hashFile(directory.resolve("tokenizer.json"), true);
        Map<String, String> configuration = new LinkedHashMap<>();
        // Serving properties can change tokenizer options; ONNX models can use companion weight files.
        // Hash the complete self-contained artifact bundle instead of guessing a subset of option names.
        try (var paths = Files.walk(directory, java.nio.file.FileVisitOption.FOLLOW_LINKS)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = directory.relativize(file).toString().replace('\\', '/');
                if (!name.equals("model.onnx") && !name.equals("tokenizer.json")) {
                    configuration.put(name, hashFile(file, false));
                }
            }
        }
        return new EmbeddingModelIdentity(model, tokenizer, configuration,
                queryPrefix == null ? "" : queryPrefix, INFERENCE_VERSION);
    }

    private static String hashFile(Path file, boolean required) throws IOException {
        if (!Files.isRegularFile(file) || required && Files.size(file) == 0) {
            throw new IOException("Required embedding artifact is missing or empty: " + file.getFileName());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireHash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Embedding artifact requires a SHA-256 identity");
        }
    }
}
