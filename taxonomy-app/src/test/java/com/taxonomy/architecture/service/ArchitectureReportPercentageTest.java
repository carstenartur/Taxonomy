package com.taxonomy.architecture.service;

import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.dto.DetectedPattern;
import com.taxonomy.dto.PatternDetectionView;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureReportPercentageTest {

    private static final String SERVICE_SOURCE =
            "com/taxonomy/architecture/service/ArchitectureReportService.java";

    private final ArchitectureReportService reportService = new ArchitectureReportService(
            null, null, null, null, null, null, null, null);

    @Test
    void rendersTheExistingZeroToHundredScaleAcrossEveryTextualFormat() throws IOException {
        PatternDetectionView patterns = new PatternDetectionView();
        patterns.setPatternCoverage(100.0 / 3.0);
        patterns.setMatchedPatterns(List.of(pattern("Complete", 100.0, false)));
        patterns.setIncompletePatterns(List.of(
                pattern("Partial", 100.0 / 3.0, true),
                pattern("Zero", 0.0, true)));

        for (String output : renderedPlainText(report(patterns))) {
            assertThat(output)
                    .contains("Pattern Coverage: 33.3%")
                    .contains("Complete — 100% complete")
                    .contains("Partial — 33% complete")
                    .contains("Zero — 0% complete")
                    .doesNotContain("10000%", "3333%");
        }
    }

    @Test
    void boundsOutOfRangeAndNonFinitePatternPercentages() throws IOException {
        PatternDetectionView patterns = new PatternDetectionView();
        patterns.setPatternCoverage(Double.POSITIVE_INFINITY);
        patterns.setMatchedPatterns(List.of(pattern("Too high", 150.0, false)));
        patterns.setIncompletePatterns(List.of(
                pattern("Negative", -10.0, true),
                pattern("Not a number", Double.NaN, true)));

        for (String output : renderedPlainText(report(patterns))) {
            assertThat(output)
                    .contains("Pattern Coverage: 0.0%")
                    .contains("Too high — 100% complete")
                    .contains("Negative — 0% complete")
                    .contains("Not a number — 0% complete")
                    .doesNotContain("150%", "-10%", "NaN%", "Infinity%");
        }
    }

    @Test
    void rendererSourceCannotReintroduceDuplicatePatternScaling() throws IOException {
        Path source = Path.of("src/main/java").resolve(SERVICE_SOURCE);
        if (!Files.exists(source)) {
            source = Path.of("taxonomy-app/src/main/java").resolve(SERVICE_SOURCE);
        }
        assertThat(Files.readString(source))
                .contains("formatBoundedPercentage")
                .doesNotContain("getPatternCoverage() * 100", "getCompleteness() * 100");
    }

    private List<String> renderedPlainText(ArchitectureReport report) throws IOException {
        return List.of(
                plainText(reportService.renderMarkdown(report)),
                plainText(reportService.renderHtml(report)),
                plainText(docxText(reportService.renderDocx(report))));
    }

    private static ArchitectureReport report(PatternDetectionView patterns) {
        ArchitectureReport report = new ArchitectureReport();
        report.setBusinessText("percentage rendering contract");
        report.setPatternDetection(patterns);
        return report;
    }

    private static DetectedPattern pattern(String name, double completeness,
                                           boolean incomplete) {
        return new DetectedPattern(
                name,
                List.of("step"),
                incomplete ? List.of() : List.of("step"),
                incomplete ? List.of("step") : List.of(),
                completeness);
    }

    private static String docxText(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            return document.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText())
                    .collect(Collectors.joining("\n"));
        }
    }

    private static String plainText(String text) {
        return text
                .replaceAll("(?s)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("**", "")
                .replace("`", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
