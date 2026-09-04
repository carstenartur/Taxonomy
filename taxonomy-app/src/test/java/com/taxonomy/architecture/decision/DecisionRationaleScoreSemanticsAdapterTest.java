package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.decision.DecisionRationaleReport.ChildDecision;
import com.taxonomy.architecture.decision.DecisionRationaleReport.DecisionChapter;
import com.taxonomy.architecture.decision.DecisionRationaleReport.Disposition;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ExecutiveSummary;
import com.taxonomy.architecture.decision.DecisionRationaleReport.LeafCandidate;
import com.taxonomy.architecture.decision.DecisionRationaleReport.PathStep;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ReasonSource;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ReportStatus;
import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.AnalysisScoreKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionRationaleScoreSemanticsAdapterTest {

    private final DecisionRationaleScoreSemanticsAdapter adapter =
            new DecisionRationaleScoreSemanticsAdapter();

    @Test
    void productSuitabilityNeverBecomesAnUnboundedParentShare() {
        AnalysisScoreDetail detail = new AnalysisScoreDetail(
                "IP-P", AnalysisScoreKind.PRODUCT_SUITABILITY,
                80, 32, "IP-F", 40);
        LeafCandidate leaf = new LeafCandidate(
                "IP-P", "Concrete product", 32, "IP", 2,
                "IP → IP-F → IP-P", "AI reason", ReasonSource.AI_SCORING);
        PathStep pathStep = new PathStep(
                3, "IP-P", "Concrete product", 32, 200.0,
                "AI reason", ReasonSource.AI_SCORING);
        ChildDecision child = new ChildDecision(
                "IP-P", "Concrete product", "Description", 32, 200.0,
                1, true, Disposition.LEAF_CANDIDATE,
                "AI reason", ReasonSource.AI_SCORING, true);
        DecisionChapter chapter = new DecisionChapter(
                1, "IP-F", "Product family", "Description", 40, 1,
                true, "summary", "comparison", List.of(child), List.of());
        DecisionRationaleReport report = new DecisionRationaleReport(
                "Report", "en", "requirement", ReportStatus.FINAL,
                null,
                new ExecutiveSummary(leaf, List.of(pathStep), "conclusion", "method"),
                List.of(chapter), List.of(leaf), List.of(), List.of(), List.of(), null);

        DecisionRationaleReport adapted = adapter.adapt(
                report, Map.of("IP-P", detail), Locale.ENGLISH);

        assertThat(adapted.executiveSummary().leadingLeaf().score()).isEqualTo(32);
        assertThat(adapted.executiveSummary().path().get(0).absoluteScore()).isEqualTo(32);
        assertThat(adapted.executiveSummary().path().get(0).localSharePercent()).isEqualTo(80.0);
        assertThat(adapted.chapters().get(0).children().get(0).absoluteScore()).isEqualTo(32);
        assertThat(adapted.chapters().get(0).children().get(0).localSharePercent()).isEqualTo(80.0);
        assertThat(adapted.chapters().get(0).children().get(0).reason())
                .contains("Product suitability: 80%", "effective relevance: 32/100");
        assertThat(adapted.scoreDetails()).containsEntry("IP-P", detail);
        assertThat(adapted.executiveSummary().methodologyNote())
                .contains("product-family relevance");
    }

    @Test
    void ordinaryHierarchySharesAreOnlyBoundedAndOtherwiseRemainUnchanged() {
        ChildDecision child = new ChildDecision(
                "CP-C", "Category", "Description", 30, 150.0,
                1, true, Disposition.LEAF_CANDIDATE,
                "reason", ReasonSource.AI_SCORING, true);
        DecisionChapter chapter = new DecisionChapter(
                1, "CP", "Capabilities", "Description", 30, 0,
                true, "summary", "comparison", List.of(child), List.of());
        DecisionRationaleReport report = new DecisionRationaleReport(
                "Report", "en", "requirement", ReportStatus.FINAL,
                null, new ExecutiveSummary(null, List.of(), "conclusion", "method"),
                List.of(chapter), List.of(), List.of(), List.of(), List.of(), null);

        DecisionRationaleReport adapted = adapter.adapt(report, Map.of(), Locale.ENGLISH);

        assertThat(adapted.chapters().get(0).children().get(0).localSharePercent())
                .isEqualTo(100.0);
    }
}
