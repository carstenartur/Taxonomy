package com.taxonomy.export.reformulation;

import org.junit.jupiter.api.Test;

class ReformulationReportRendererTest {
    @Test void externalMarkupRemainsLiteral() { ReformulationReportRendererChecks.literalMarkup(); }
    @Test void decisionHistoryAndMergedOriginsRemainVisible() { ReformulationReportRendererChecks.historyAndOrigins(); }
    @Test void inputSnapshotsAreImmutableAndDeterministic() { ReformulationReportRendererChecks.immutableInputs(); }
    @Test void headingsKeepTildesLiteral() { ReformulationReportRendererChecks.literalTildesInHeadings(); }
}
