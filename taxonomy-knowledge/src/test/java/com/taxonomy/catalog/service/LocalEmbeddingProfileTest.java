package com.taxonomy.catalog.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalEmbeddingProfileTest {
    @Test
    void defaultModelSupportsCrossLanguageRetrieval() {
        assertThat(new LocalEmbeddingService().effectiveModelUrl())
                .isEqualTo("https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2");
    }

    @Test
    void defaultQueryDoesNotAddAnInstructionUnusedByTheMultilingualModel() throws Exception {
        var service = new RecordingEmbeddings();
        service.embedQuery("Ein Schreiben überarbeiten");
        assertThat(service.input).isEqualTo("Ein Schreiben überarbeiten");
    }

    @Test
    void defaultCriteriaUsesMaskedMeanPoolingAtTheTrainedTokenLimit() {
        var arguments = LocalEmbeddingService.modelCriteria(java.nio.file.Path.of("not-loaded")).getArguments();
        assertThat(arguments).containsEntry("pooling", "mean").containsEntry("maxLength", 128);
    }

    @Test
    void explicitBgeRetainsItsClsPoolingAndAsymmetricInstruction() throws Exception {
        var service = new RecordingEmbeddings();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelProfile", EmbeddingModelProfile.BGE_SMALL_EN);
        service.embedQuery("Revise a letter");
        assertThat(service.input).isEqualTo("Represent this sentence for searching relevant passages: Revise a letter");
        service.embedDocument("Word Processing Applications");
        assertThat(service.input).isEqualTo("Word Processing Applications");
        assertThat(service.configuredModelId()).isEqualTo("bge-small-en-v1.5");
        var arguments = LocalEmbeddingService.modelCriteria(java.nio.file.Path.of("not-loaded"),
                EmbeddingModelProfile.BGE_SMALL_EN).getArguments();
        assertThat(arguments).containsEntry("pooling", "cls").containsEntry("maxLength", 512);
    }

    @Test
    void explicitEmptyQueryPrefixIsRespectedInsteadOfRestoringTheProfileInstruction() throws Exception {
        var service = new RecordingEmbeddings();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelProfile", EmbeddingModelProfile.BGE_SMALL_EN);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "queryPrefix", "");
        service.embedQuery("Revise a letter");
        assertThat(service.input).isEqualTo("Revise a letter");
    }

    @Test
    void safeModelIdDoesNotExposeConfiguredUrlsOrFilesystemPaths() {
        var service = new LocalEmbeddingService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelName", "https://private.invalid/?token=secret");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelDir", "/private/model-location");
        assertThat(service.configuredModelId()).isEqualTo("sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2");
    }

    private static final class RecordingEmbeddings extends LocalEmbeddingService {
        private String input;
        @Override public float[] embed(String text) {
            input = text;
            return new float[384];
        }
    }
}
