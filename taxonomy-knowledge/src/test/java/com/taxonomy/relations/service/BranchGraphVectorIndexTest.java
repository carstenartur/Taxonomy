package com.taxonomy.relations.service;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;

class BranchGraphVectorIndexTest {
    @Test void reopensAStoredNativeGenerationAndSearchesEachDocumentTypeIndependently() throws IOException {
        BranchGraphVectorIndex.Archive bytes;
        try (var writer = new BranchGraphVectorIndex.Builder("a-main")) {
            writer.node("{\"code\":\"A\"}", "node text", vector(0));
            writer.node("{\"code\":\"B\"}", "node text", vector(1));
            writer.relation("CP", "IP", "CP", "SUPPORTS", "relation text", vector(1));
            bytes = writer.finish();
        }
        var hits = BranchGraphVectorIndex.search(bytes, vector(1), 1, 2);
        assertThat(hits.nodes()).containsExactly("{\"code\":\"B\"}");
        assertThat(hits.relations()).containsExactly(new BranchGraphVectorIndex.RelationHit("CP", "SUPPORTS"));
        assertThatThrownBy(() -> BranchGraphVectorIndex.search(
                new BranchGraphVectorIndex.Archive(bytes.bytes(), 3, 1, "a-main"), vector(1), 2, 2))
                .isInstanceOf(IOException.class);
    }

    @Test void brokenVectorsAndIncompleteArchivesCannotPublishReadiness() throws IOException {
        try (var writer = new BranchGraphVectorIndex.Builder("a-main")) {
            assertThatThrownBy(() -> writer.node("A", "node text", new float[384])).isInstanceOf(IllegalArgumentException.class);
            var invalid = vector(3);
            invalid[0] = Float.NaN;
            assertThatThrownBy(() -> writer.relation("CP", "IP", "CP", "SUPPORTS", "relation text", invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(writer::finish).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> BranchGraphVectorIndex.search(
                new BranchGraphVectorIndex.Archive(new byte[]{1, 2}, 1, 1, "a-main"), vector(0), 1, 1))
                .isInstanceOf(IOException.class);
    }

    private static float[] vector(int index) {
        float[] result = new float[384];
        result[index] = 1f;
        return result;
    }
}
