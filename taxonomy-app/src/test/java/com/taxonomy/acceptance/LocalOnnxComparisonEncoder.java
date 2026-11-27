package com.taxonomy.acceptance;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Set;

/** Dimension-independent, CPU-only inference for the pinned comparison graphs. */
final class LocalOnnxComparisonEncoder {
    private static final String OUTPUT = "last_hidden_state";
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final int dimensions;
    private final int maxTokens;
    private final boolean tokenTypes;

    LocalOnnxComparisonEncoder(OrtEnvironment environment, OrtSession session, HuggingFaceTokenizer tokenizer,
            int dimensions, int maxTokens, boolean tokenTypes) {
        this.environment = environment;
        this.session = session;
        this.tokenizer = tokenizer;
        this.dimensions = dimensions;
        this.maxTokens = maxTokens;
        this.tokenTypes = tokenTypes;
        var expected = tokenTypes ? Set.of("input_ids", "attention_mask", "token_type_ids")
                : Set.of("input_ids", "attention_mask");
        if (!session.getInputNames().equals(expected) || !session.getOutputNames().equals(Set.of(OUTPUT))) {
            throw new IllegalArgumentException("Unexpected model graph inputs or outputs");
        }
    }

    static HuggingFaceTokenizer tokenizer(Path bundle, int maxTokens) throws IOException {
        return HuggingFaceTokenizer.builder().optTokenizerPath(bundle.resolve("tokenizer.json"))
                .optTokenizerConfigPath(bundle.resolve("tokenizer_config.json").toString())
                .optAddSpecialTokens(true).optPadding(false).optTruncation(true).optMaxLength(maxTokens).build();
    }

    float[] embed(String text) throws OrtException {
        var encoding = tokenizer.encode(text);
        long[] ids = encoding.getIds();
        long[] mask = encoding.getAttentionMask();
        if (ids.length == 0 || ids.length > maxTokens || ids.length != mask.length) {
            throw new IllegalArgumentException("Tokenizer exceeded the configured input contract");
        }
        try (var inputIds = OnnxTensor.createTensor(environment, new long[][]{ids});
             var attentionMask = OnnxTensor.createTensor(environment, new long[][]{mask});
             var typeIds = tokenTypes ? OnnxTensor.createTensor(environment, new long[][]{encoding.getTypeIds()}) : null) {
            var inputs = new LinkedHashMap<String, OnnxTensor>();
            inputs.put("input_ids", inputIds);
            inputs.put("attention_mask", attentionMask);
            if (typeIds != null) inputs.put("token_type_ids", typeIds);
            try (var result = session.run(inputs)) {
                var hidden = result.get(OUTPUT).orElseThrow().getValue();
                if (!(hidden instanceof float[][][] states) || states.length != 1) {
                    throw new IllegalArgumentException("Expected a single sequence of float hidden states");
                }
                return LocalEmbeddingComparisonSupport.pool(states[0], mask, dimensions);
            }
        }
    }
}
