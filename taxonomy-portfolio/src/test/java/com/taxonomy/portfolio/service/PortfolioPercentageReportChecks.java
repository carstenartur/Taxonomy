package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Exercises the public report entry point with authored data, without a Spring context or database. */
final class PortfolioPercentageReportChecks {
    private PortfolioPercentageReportChecks() { }

    static void check(PortfolioReportService.Format format, double percentage, String expected) throws Exception {
        var project = new ProjectView(1L, "fixture", "Fixture", "", null, "author", "workspace",
                null, null, null, null, null, null, 1, 0, 0);
        var requirement = new RequirementView(1L, 1L, "requirement", "Requirement", null, 1,
                null, null, null, "author", null, null, null, null, null);
        var metrics = new PortfolioMetrics(1, 1, 0, 1, 0, Map.of(), 0, 0, 0, 0);
        var matrix = new MatrixView(List.of(), List.of(), Map.of());
        var node = new AggregatedTaxonomyNode("fixture-node", "Function", "CP", 1,
                percentage / 100, percentage, List.of("requirement"), List.of(), Map.of());
        var view = new ProjectPortfolioView(project, metrics, List.of(requirement), List.of(node),
                List.of(), List.of(), matrix, matrix, matrix);
        var aggregation = new PortfolioAggregationService(null, null, null, null, null, null, null, 30) {
            @Override public ProjectPortfolioView build(Long projectId, String username, WorkspaceContext context) {
                return view;
            }
        };
        var report = new PortfolioReportService(aggregation, null, JsonMapper.builder().build())
                .render(1L, null, format, "taxonomy", "author", null);
        if (format == PortfolioReportService.Format.DOCX) {
            try (var document = new XWPFDocument(new ByteArrayInputStream(report.bytes()))) {
                var row = document.getTables().getFirst().getRow(1);
                equal("1", row.getCell(2).getText()); // Counts remain integers.
                equal(expected, row.getCell(3).getText());
                equal(expected, row.getCell(4).getText());
            }
        } else if (format == PortfolioReportService.Format.MARKDOWN) {
            String text = new String(report.bytes(), StandardCharsets.UTF_8);
            String expectedRow = "|fixture-node|Function|1|" + expected + "|" + expected + "|";
            if (!text.contains(expectedRow)) throw new AssertionError("Missing exact percentage row: " + expectedRow + "\n" + text);
        } else {
            throw new IllegalArgumentException("Not a supported display format: " + format);
        }
    }

    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but was " + actual);
    }
}
