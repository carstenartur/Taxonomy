package com.taxonomy.catalog.service;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class LocalEmbeddingModelCriteriaTest {
    @Test
    void bgeExplicitlyUsesClsAndUnitNormalization() {
        var arguments = LocalEmbeddingService.modelCriteria(Path.of("not-loaded"), EmbeddingModelProfile.BGE_SMALL_EN).getArguments();
        assertEquals("cls", arguments.get("pooling"));
        assertEquals(Boolean.TRUE, arguments.get("normalize"));
        assertEquals(Boolean.TRUE, arguments.get("includeTokenTypes"));
    }

    @Test
    void preservesTheModelEngineAndTranslatorBoundary() {
        var criteria = LocalEmbeddingService.modelCriteria(Path.of("not-loaded"), EmbeddingModelProfile.BGE_SMALL_EN);
        assertEquals("OnnxRuntime", criteria.getEngine());
        assertEquals("model", criteria.getModelName());
        assertEquals(String.class, criteria.getInputClass());
        assertEquals(float[].class, criteria.getOutputClass());
        assertInstanceOf(TextEmbeddingTranslatorFactory.class, criteria.getTranslatorFactory());
    }

    @Test
    @Tag("onnx")
    void realMultilingualTranslatorUsesAttentionMaskedMeanRatherThanClsOrPadding() {
        LocalEmbeddingPoolingChecks.normalizedMeanIncludesRealTokensButExcludesMaskedPadding();
    }

    @Test
    @Tag("onnx")
    void realTranslatorSelectsAndNormalizesOnlyTheClsToken() {
        LocalEmbeddingPoolingChecks.normalizedClsIsIndependentOfOtherTokens();
    }
}
