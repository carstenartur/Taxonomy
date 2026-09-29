package com.taxonomy.catalog.service;

import ai.djl.Device;
import ai.djl.Model;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.translator.TextEmbeddingTranslator;
import ai.djl.metric.Metrics;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Block;
import ai.djl.translate.TranslatorContext;

import java.nio.file.Path;

/** Exercises DJL's real postprocessing on hand-computed tensors; not model-quality evidence. */
final class LocalEmbeddingPoolingChecks {
    private LocalEmbeddingPoolingChecks() { }

    static void normalizedClsIsIndependentOfOtherTokens() {
        var criteria = LocalEmbeddingService.modelCriteria(Path.of("model-not-loaded"));
        // This is the same builder/argument path used by TextEmbeddingTranslatorFactory.
        // Tokenization is not part of this postprocessing-only test.
        var translator = TextEmbeddingTranslator.builder(
                (HuggingFaceTokenizer) null, criteria.getArguments()).build();
        try (var manager = NDManager.newBaseManager(Device.cpu(), "OnnxRuntime")) {
            float[] hidden = new float[3 * 384];
            hidden[0] = 3;
            hidden[1] = 4;
            hidden[384] = -3;
            hidden[385] = 4;
            // A masked padding token must not affect the result either.
            hidden[2 * 384 + 2] = 1000;
            NDArray states = manager.create(hidden, new Shape(1, 3, 384));
            states.setName("last_hidden_state");
            NDArray mask = manager.create(new long[]{1, 1, 0}, new Shape(1, 3));
            float[] actual = translator.processOutput(new OutputContext(manager, mask), new NDList(states));
            if (actual.length != 384) throw new AssertionError("Expected one 384-dimensional embedding");
            for (int i = 0; i < actual.length; i++) {
                float expected = i == 0 ? 0.6f : i == 1 ? 0.8f : 0.0f;
                if (!Float.isFinite(actual[i]) || Math.abs(actual[i] - expected) > 1e-6f) {
                    throw new AssertionError("Normalized CLS component " + i + ": expected "
                            + expected + ", actual " + actual[i]);
                }
            }
        }
    }

    private record OutputContext(NDManager manager, NDArray mask) implements TranslatorContext {
        @Override public NDManager getNDManager() { return manager; }
        @Override public NDManager getPredictorManager() { return manager; }
        @Override public Object getAttachment(String key) {
            if ("attentionMask".equals(key)) return mask;
            throw new IllegalArgumentException("Unexpected attachment: " + key);
        }
        @Override public void setAttachment(String key, Object value) { throw new UnsupportedOperationException(); }
        @Override public Model getModel() { throw new UnsupportedOperationException(); }
        @Override public Block getBlock() { throw new UnsupportedOperationException(); }
        @Override public Metrics getMetrics() { throw new UnsupportedOperationException(); }
        // The enclosing try-with-resources owns the manager and all arrays.
        @Override public void close() { }
    }
}
