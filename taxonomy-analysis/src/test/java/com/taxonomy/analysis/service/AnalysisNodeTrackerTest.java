package com.taxonomy.analysis.service;

import com.taxonomy.dto.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnalysisNodeTrackerTest {
    @Test void excludesOnlyDescendantsOfSuccessfulZerosAndKeepsTaxonomyDenominators() {
        var tracker = new AnalysisNodeTracker();
        tracker.plan(List.of(node("BP", node("BP-1", node("BP-2"))), node("IP", node("IP-1"))));
        assertThat(tracker.snapshot().total()).isEqualTo(5);
        assertThat(tracker.snapshot().open()).isEqualTo(5);
        var detail = new LlmCallDetail(); detail.setScores(Map.of("BP", 0));
        tracker.accept(detail); tracker.accept(detail);
        var progress = tracker.snapshot();
        assertThat(progress.assessed()).isEqualTo(1);
        assertThat(progress.excluded()).isEqualTo(2);
        assertThat(progress.open()).isEqualTo(2);
        assertThat(progress.taxonomies().getFirst().total()).isEqualTo(3);
        detail.setScores(Map.of("IP", 0)); detail.setError("provider failed"); tracker.accept(detail);
        assertThat(tracker.snapshot()).isEqualTo(progress);
        detail.setError(null); detail.setScores(Map.of("IP", 70)); tracker.accept(detail);
        assertThat(tracker.snapshot().open()).isEqualTo(1);
        assertThat(tracker.snapshot().excluded()).isEqualTo(2);
    }
    private static TaxonomyNodeDto node(String code, TaxonomyNodeDto... children) {
        var node = new TaxonomyNodeDto(); node.setCode(code); node.setNameEn(code);
        node.setChildren(List.of(children)); return node;
    }
}
