package com.taxonomy.reporting.render.document;
import org.junit.jupiter.api.Test;
class FractionalReportTest {
    @Test void pdfKeepsFractionalScoresVisible() throws Exception { FractionalReportChecks.pdf(null); }
    @Test void bothMermaidPresentationsKeepFractions() { FractionalReportChecks.mermaid(); }
    @Test void svgPreservesTinyPositiveScoresAsValidXml() throws Exception { FractionalReportChecks.svg(); }
    @Test void wordElementTableKeepsFractionalScores() throws Exception { FractionalReportChecks.word(null); }
}
