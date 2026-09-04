package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.decision.DecisionRationaleReport.ChildDecision;
import com.taxonomy.architecture.decision.DecisionRationaleReport.DecisionChapter;
import com.taxonomy.architecture.decision.DecisionRationaleReport.Disposition;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ExecutiveSummary;
import com.taxonomy.architecture.decision.DecisionRationaleReport.LeafCandidate;
import com.taxonomy.architecture.decision.DecisionRationaleReport.PathStep;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ReasonSource;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ReportMetadata;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ReportStatus;
import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.AnalysisScoreKind;
import com.taxonomy.dto.AnalysisScoreSemanticsFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                metadata("base-analysis-fingerprint"),
                new ExecutiveSummary(leaf, List.of(pathStep), "conclusion", "method"),
                List.of(chapter), List.of(leaf), List.of(), List.of(), List.of(), null);

        Map<String, AnalysisScoreDetail> details = Map.of("IP-P", detail);
        DecisionRationaleReport adapted = adapter.adapt(
                report, details, Locale.ENGLISH);

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
        assertThat(adapted.metadata().analysisSnapshotFingerprintSha256()).isEqualTo(
                AnalysisScoreSemanticsFingerprint.extend(
                        "base-analysis-fingerprint", details));

        DecisionRationaleReport adaptedAgain = adapter.adapt(
                adapted, details, Locale.ENGLISH);
        assertThat(adaptedAgain.metadata().analysisSnapshotFingerprintSha256())
                .isEqualTo(adapted.metadata().analysisSnapshotFingerprintSha256());
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

    @Test
    void canonicalScoreDetailKeyCollisionsFailClosed() {
        AnalysisScoreDetail detail = new AnalysisScoreDetail(
                "IP-P", AnalysisScoreKind.PRODUCT_SUITABILITY,
                80, 32, "IP-F", 40);
        Map<String, AnalysisScoreDetail> details = new LinkedHashMap<>();
        details.put("IP-P", detail);
        details.put("IP-P ", detail);

        assertThatThrownBy(() -> adapter.enrichReasons(
                Map.of(), details, Locale.ENGLISH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical node code IP-P");
    }

    private ReportMetadata metadata(String analysisFingerprint) {
        return new ReportMetadata(
                Instant.EPOCH,
                "user",
                "1.0",
                "build",
                "catalogue",
                "version",
                "catalogue-resource-fingerprint",
                "taxonomy-data-fingerprint",
                analysisFingerprint,
                "source",
                3,
                1,
                "repository",
                "workspace",
                "main",
                "commit",
                Instant.EPOCH,
                false,
                false,
                "provider",
                "SUCCESS",
                "model",
                "snapshot",
                1L,
                2L,
                3L,
                1,
                Instant.EPOCH,
                "user",
                "recorded-taxonomy-fingerprint",
                "prompt-fingerprint",
                true,
                "Europe/Berlin",
                1,
                3,
                3,
                100.0);
    }
}
