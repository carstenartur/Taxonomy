package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class LocalEmbeddingComparisonSupportTest {
    @TempDir Path temp;

    @Test
    void cosineRankingPreservesNegativeScoresAndBreaksEqualScoresByCode() {
        var vectors = Map.of("Z", new float[]{1, 0}, "A", new float[]{1, 0},
                "C", new float[]{0.6f, 0.8f}, "B", new float[]{-1, 0});
        assertThat(LocalEmbeddingComparisonSupport.rank(new float[]{1, 0}, vectors))
                .containsExactly("A", "Z", "C", "B");
    }

    @Test
    void meanPoolingExcludesPaddingAndNormalizesTheRealTokenMean() {
        float[] vector = LocalEmbeddingComparisonSupport.pool(
                new float[][]{{3, 0}, {3, 8}, {999, 0}}, new long[]{1, 1, 0}, 2);
        assertArrayEquals(new float[]{0.6f, 0.8f}, vector, 1e-6f);
        assertThatThrownBy(() -> LocalEmbeddingComparisonSupport.pool(
                new float[][]{{3, 0}}, new long[]{0}, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingAndChangedAssetBytesFailAgainstAHashPin() throws IOException {
        String pin = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        var expected = Map.of("tokenizer.json", pin);
        assertThatThrownBy(() -> LocalEmbeddingComparisonSupport.verifyFiles(temp, expected))
                .isInstanceOf(IOException.class).hasMessageContaining("tokenizer.json");
        Files.writeString(temp.resolve("tokenizer.json"), "abc");
        assertThat(LocalEmbeddingComparisonSupport.verifyFiles(temp, expected)).containsExactlyEntriesOf(expected);
        Files.writeString(temp.resolve("tokenizer.json"), "abd");
        assertThatThrownBy(() -> LocalEmbeddingComparisonSupport.verifyFiles(temp, expected))
                .isInstanceOf(IOException.class).hasMessageContaining("tokenizer.json");
    }

    @Test
    void fullRanksKeepMissesAndTargetedDistractorObservationsDistinct() {
        var positive = new OnnxMultilingualReferenceEvaluation.Case("p", "de", "AMBIGUOUS", "q",
                Set.of("A", "C"), Set.of());
        var negative = new OnnxMultilingualReferenceEvaluation.Case("n", "en", "DISTRACTOR", "q",
                Set.of(), Set.of("A", "C"));
        var ranking = List.of("A", "B", "C");
        var measured = LocalEmbeddingComparisonSupport.measure(positive, ranking, 2);
        assertThat(measured.targetRanks()).containsExactlyInAnyOrderEntriesOf(Map.of("A", 1, "C", 3));
        assertThat(measured.missing()).containsExactly("C");
        assertThat(measured.recall()).isEqualTo(0.5);
        assertThat(measured.reciprocalRank()).isEqualTo(1.0);
        var distractor = LocalEmbeddingComparisonSupport.measure(negative, ranking, 2);
        assertThat(distractor.recall()).isNull();
        assertThat(distractor.unexpectedTargets()).containsExactly("A");
    }
}
