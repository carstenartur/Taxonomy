package com.taxonomy.reporting.render.decision;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DecisionReportInventoryTest {
    @Test void mandatorySourceNoticeIncludesCompleteSelectionCounts() {
        var report = DecisionRationaleTemplateRendererTest.report();
        String notice = String.join("\n", DecisionReportPresentation.sourceNotice(report));
        long expected = report.scope().decisionTree().rows().stream().map(row -> row.code()).distinct().count();
        assertThat(notice).contains(new DecisionReportLabels(report.languageTag()).german()
                ? "Knoten der Berichtsauswahl (einschließlich Navigation): " + expected
                : "Report selection nodes (including navigation): " + expected);
    }
}
