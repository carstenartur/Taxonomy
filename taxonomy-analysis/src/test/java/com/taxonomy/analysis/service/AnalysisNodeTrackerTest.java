package com.taxonomy.analysis.service;

import com.taxonomy.dto.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnalysisNodeTrackerTest {
    @Test void reportsPositiveNodesAndLeafAllocationWithoutCountingAncestorBudgetsTwice() {
        var tracker = new AnalysisNodeTracker();
        tracker.plan(List.of(node("BP", node("BP-1", node("BP-2"), node("BP-3")))));
        var detail = new LlmCallDetail();
        detail.setScores(Map.of("BP", 80, "BP-1", 80, "BP-2", 0.125d));
        tracker.accept(detail); tracker.accept(detail);
        var part = tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(tracker.snapshot())
                .path("taxonomies").get(0);
        assertThat(part.path("positive").asInt()).isEqualTo(3);
        assertThat(part.path("allocatedPercent").asDouble()).isEqualTo(0.125d);
        assertThat(part.path("rootPercent").asDouble()).isEqualTo(80d);
        detail.setScores(Map.of("BP-3", 79.875d)); tracker.accept(detail);
        part = tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(tracker.snapshot()).path("taxonomies").get(0);
        assertThat(part.path("positive").asInt()).isEqualTo(4);
        assertThat(part.path("allocatedPercent").asDouble()).isEqualTo(80d);
        detail.setScores(Map.of("BP-2", 0)); tracker.accept(detail);
        part = tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(tracker.snapshot()).path("taxonomies").get(0);
        assertThat(part.path("positive").asInt()).isEqualTo(3);
        assertThat(part.path("allocatedPercent").asDouble()).isEqualTo(79.875d);
    }

    @Test void independentProductSuitabilityDoesNotInflateTheDistributedFamilyBudget() {
        var first = node("IP-product-a"); first.setAnalysisRole("PRODUCT");
        var second = node("IP-product-b"); second.setAnalysisRole("PRODUCT");
        var family = node("IP-family", first, second); family.setAnalysisRole("PRODUCT_FAMILY");
        var tracker = new AnalysisNodeTracker(); tracker.plan(List.of(node("IP", family)));
        var detail = new LlmCallDetail();
        detail.setScores(Map.of("IP", 80, "IP-family", 60, "IP-product-a", 90, "IP-product-b", 95));
        tracker.accept(detail);
        var part = tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(tracker.snapshot()).path("taxonomies").get(0);
        assertThat(part.path("positive").asInt()).isEqualTo(4);
        assertThat(part.path("allocatedPercent").asDouble()).isEqualTo(60d);
        assertThat(part.path("rootPercent").asDouble()).isEqualTo(80d);
    }

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
    @Test void positiveSubpercentAssessmentDoesNotPruneDescendants() {
        var tracker = new AnalysisNodeTracker();
        tracker.plan(List.of(node("BP", node("BP-1", node("BP-2")))));
        var detail = new LlmCallDetail(); detail.setScores(Map.of("BP", 0.125d));

        tracker.accept(detail);

        assertThat(tracker.snapshot().assessed()).isEqualTo(1);
        assertThat(tracker.snapshot().excluded()).isZero();
        assertThat(tracker.snapshot().open()).isEqualTo(2);
    }
    private static TaxonomyNodeDto node(String code, TaxonomyNodeDto... children) {
        var node = new TaxonomyNodeDto(); node.setCode(code); node.setNameEn(code);
        node.setChildren(List.of(children)); return node;
    }
}
