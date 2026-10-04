package com.taxonomy.analysis.dag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisWorkerShardsTest {

    @Test
    void blankSelectsAllEightCatalogueRoots() {
        assertThat(AnalysisWorkerShards.parse(null).roots()).isEqualTo(TaxonomyShardRoot.DEFAULT_ROOTS);
        assertThat(AnalysisWorkerShards.parse("  ").roots()).hasSize(8);
    }

    @Test
    void parsesAnOrderedRootList() {
        assertThat(AnalysisWorkerShards.parse(" CP, IP ").roots())
                .containsExactly(TaxonomyShardRoot.of("CP"), TaxonomyShardRoot.of("IP"));
    }

    @Test
    void unknownDuplicateAndEmptyRootsFailClosed() {
        assertThatThrownBy(() -> AnalysisWorkerShards.parse("CP,XX")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalysisWorkerShards.parse("CP,CP")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalysisWorkerShards.parse("CP,,IP")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisWorkerShards(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noHandlersBindNoTaskFamily() {
        assertThat(AnalysisTaskHandlers.NONE.handles(AnalysisTaskType.SUBTAXONOMY_ANALYSIS)).isFalse();
        assertThat(AnalysisTaskHandlers.NONE.handles(AnalysisTaskType.RELATION_ANALYSIS)).isFalse();
    }
}
