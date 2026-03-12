package com.taxonomy.service;

import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.*;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.MermaidExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates data from existing services and produces an {@link ArchitectureReport},
 * which can then be rendered as Markdown, HTML, or DOCX.
 */
@Service
public class ArchitectureReportService {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureReportService.class);
    private static final int DEFAULT_MIN_SCORE = 20;

    private final RequirementArchitectureViewService architectureViewService;
    private final ArchitectureGapService gapService;
    private final ArchitecturePatternService patternService;
    private final ArchitectureRecommendationService recommendationService;
    private final DiagramProjectionService diagramProjectionService;
    private final MermaidExportService mermaidExportService;
    private final RelationProposalService proposalService;

    public ArchitectureReportService(RequirementArchitectureViewService architectureViewService,
                                      ArchitectureGapService gapService,
                                      ArchitecturePatternService patternService,
                                      ArchitectureRecommendationService recommendationService,
                                      DiagramProjectionService diagramProjectionService,
                                      MermaidExportService mermaidExportService,
                                      RelationProposalService proposalService) {
        this.architectureViewService = architectureViewService;
        this.gapService = gapService;
        this.patternService = patternService;
        this.recommendationService = recommendationService;
        this.diagramProjectionService = diagramProjectionService;
        this.mermaidExportService = mermaidExportService;
        this.proposalService = proposalService;
    }

    /**
     * Generates a full architecture report by calling all existing analysis services.
     *
     * @param scores       nodeCode → score map (0–100)
     * @param businessText the business requirement text
     * @param minScore     minimum score threshold (0 → default 20)
     * @return populated {@link ArchitectureReport}
     */
    @Transactional(readOnly = true)
    public ArchitectureReport generateReport(Map<String, Integer> scores,
                                              String businessText, int minScore) {
        ArchitectureReport report = new ArchitectureReport();
        report.setBusinessText(businessText);
        report.setScores(scores != null ? scores : Map.of());
        report.setGeneratedAt(Instant.now());

        int threshold = minScore > 0 ? minScore : DEFAULT_MIN_SCORE;
        Map<String, Integer> safeScores = scores != null ? scores : Map.of();

        // 1. Architecture View
        RequirementArchitectureView archView = architectureViewService.build(
                safeScores, businessText, 20);
        report.setArchitectureView(archView);

        // 2. Gap Analysis
        GapAnalysisView gaps = gapService.analyze(safeScores, businessText, threshold);
        report.setGapAnalysis(gaps);

        // 3. Pattern Detection
        PatternDetectionView patterns = patternService.detectForScores(safeScores, threshold);
        report.setPatternDetection(patterns);

        // 4. Recommendation
        ArchitectureRecommendation recommendation = recommendationService.recommend(
                safeScores, businessText, threshold);
        report.setRecommendation(recommendation);

        // 5. Pending Proposals
        List<RelationProposalDto> pending = proposalService.getPendingProposals();
        report.setPendingProposals(pending);

        // 6. Mermaid Diagram
        String title = businessText != null && businessText.length() > 60
                ? businessText.substring(0, 57) + "..."
                : (businessText != null ? businessText : "Report");
        DiagramModel diagram = diagramProjectionService.project(archView, title);
        String mermaid = mermaidExportService.export(diagram);
        report.setMermaidDiagram(mermaid);

        log.info("Report generated: {} anchors, {} gaps, {} patterns, {} recommendations",
                archView.getTotalAnchors(),
                gaps.getTotalGaps(),
                patterns.getMatchedPatterns().size(),
                recommendation.getConfirmedElements().size());

        return report;
    }

    /**
     * Renders an {@link ArchitectureReport} as Markdown text.
     */
    public String renderMarkdown(ArchitectureReport report) {
        StringBuilder md = new StringBuilder();

        md.append("# Architecture Analysis Report\n\n");

        // Metadata
        md.append("**Generated:** ").append(formatTimestamp(report.getGeneratedAt())).append("  \n");
        md.append("**Business Requirement:** ").append(
                report.getBusinessText() != null ? report.getBusinessText() : "N/A").append("\n\n");

        // Architecture Summary
        md.append("## 1. Architecture Summary\n\n");
        RequirementArchitectureView view = report.getArchitectureView();
        if (view != null) {
            md.append("| Metric | Value |\n|---|---|\n");
            md.append("| Anchors | ").append(view.getTotalAnchors()).append(" |\n");
            md.append("| Elements | ").append(view.getTotalElements()).append(" |\n");
            md.append("| Relationships | ").append(view.getTotalRelationships()).append(" |\n");
            md.append("| Max Hop Distance | ").append(view.getMaxHopDistance()).append(" |\n\n");

            if (!view.getAnchors().isEmpty()) {
                md.append("### Anchor Nodes\n\n");
                md.append("| Node | Score | Reason |\n|---|---|---|\n");
                for (RequirementAnchor anchor : view.getAnchors()) {
                    md.append("| `").append(anchor.getNodeCode()).append("` | ");
                    md.append(anchor.getDirectScore()).append("% | ");
                    md.append(anchor.getReason() != null ? anchor.getReason() : "—").append(" |\n");
                }
                md.append("\n");
            }
        }

        // Gap Analysis
        md.append("## 2. Gap Analysis\n\n");
        GapAnalysisView gaps = report.getGapAnalysis();
        if (gaps != null) {
            md.append("**Total Gaps:** ").append(gaps.getTotalGaps()).append("\n\n");

            if (!gaps.getMissingRelations().isEmpty()) {
                md.append("### Missing Relations\n\n");
                md.append("| Source | Expected Type | Expected Target Root | Description |\n|---|---|---|---|\n");
                for (MissingRelation mr : gaps.getMissingRelations()) {
                    md.append("| `").append(mr.getSourceNodeCode()).append("` | ");
                    md.append(mr.getExpectedRelationType()).append(" | ");
                    md.append(mr.getExpectedTargetRoot()).append(" | ");
                    md.append(mr.getDescription() != null ? mr.getDescription() : "—").append(" |\n");
                }
                md.append("\n");
            }

            if (!gaps.getIncompletePatterns().isEmpty()) {
                md.append("### Incomplete Patterns\n\n");
                md.append("| Node | Pattern | Missing |\n|---|---|---|\n");
                for (IncompletePattern ip : gaps.getIncompletePatterns()) {
                    md.append("| `").append(ip.getNodeCode()).append("` | ");
                    md.append(ip.getPatternDescription()).append(" | ");
                    md.append(ip.getMissingElement() != null ? ip.getMissingElement() : "—").append(" |\n");
                }
                md.append("\n");
            }
        }

        // Pattern Detection
        md.append("## 3. Detected Patterns\n\n");
        PatternDetectionView patterns = report.getPatternDetection();
        if (patterns != null) {
            md.append("**Pattern Coverage:** ").append(
                    String.format("%.1f%%", patterns.getPatternCoverage() * 100)).append("\n\n");

            if (!patterns.getMatchedPatterns().isEmpty()) {
                md.append("### Matched Patterns\n\n");
                for (DetectedPattern dp : patterns.getMatchedPatterns()) {
                    md.append("- **").append(dp.getPatternName()).append("** — ");
                    md.append(String.format("%.0f%%", dp.getCompleteness() * 100)).append(" complete");
                    if (!dp.getPresentSteps().isEmpty()) {
                        md.append(" (").append(String.join(", ", dp.getPresentSteps())).append(")");
                    }
                    md.append("\n");
                }
                md.append("\n");
            }

            if (!patterns.getIncompletePatterns().isEmpty()) {
                md.append("### Incomplete Patterns\n\n");
                for (DetectedPattern dp : patterns.getIncompletePatterns()) {
                    md.append("- **").append(dp.getPatternName()).append("** — ");
                    md.append(String.format("%.0f%%", dp.getCompleteness() * 100)).append(" complete, missing: ");
                    md.append(String.join(", ", dp.getMissingSteps()));
                    md.append("\n");
                }
                md.append("\n");
            }
        }

        // Recommendations
        md.append("## 4. Recommendations\n\n");
        ArchitectureRecommendation rec = report.getRecommendation();
        if (rec != null) {
            md.append("**Confidence:** ").append(String.format("%.1f%%", rec.getConfidence())).append("\n\n");

            if (!rec.getConfirmedElements().isEmpty()) {
                md.append("### Confirmed Elements\n\n");
                md.append("| Node | Title | Root | Score | Reasoning |\n|---|---|---|---|---|\n");
                for (RecommendedElement el : rec.getConfirmedElements()) {
                    md.append("| `").append(el.getNodeCode()).append("` | ");
                    md.append(el.getTitle()).append(" | ");
                    md.append(el.getTaxonomyRoot()).append(" | ");
                    md.append(el.getScore()).append("% | ");
                    md.append(el.getReasoning() != null ? el.getReasoning() : "—").append(" |\n");
                }
                md.append("\n");
            }

            if (!rec.getProposedElements().isEmpty()) {
                md.append("### Proposed Elements\n\n");
                md.append("| Node | Title | Root | Reasoning |\n|---|---|---|---|\n");
                for (RecommendedElement el : rec.getProposedElements()) {
                    md.append("| `").append(el.getNodeCode()).append("` | ");
                    md.append(el.getTitle()).append(" | ");
                    md.append(el.getTaxonomyRoot()).append(" | ");
                    md.append(el.getReasoning() != null ? el.getReasoning() : "—").append(" |\n");
                }
                md.append("\n");
            }

            if (!rec.getSuggestedRelations().isEmpty()) {
                md.append("### Suggested Relations\n\n");
                md.append("| Source | → | Target | Type | Reasoning |\n|---|---|---|---|---|\n");
                for (SuggestedRelation sr : rec.getSuggestedRelations()) {
                    md.append("| `").append(sr.getSourceCode()).append("` | → | ");
                    md.append("`").append(sr.getTargetCode()).append("` | ");
                    md.append(sr.getRelationType()).append(" | ");
                    md.append(sr.getReasoning() != null ? sr.getReasoning() : "—").append(" |\n");
                }
                md.append("\n");
            }
        }

        // Pending Proposals
        if (!report.getPendingProposals().isEmpty()) {
            md.append("## 5. Pending Relation Proposals\n\n");
            md.append("| Source | Target | Type | Confidence | Status |\n|---|---|---|---|---|\n");
            for (RelationProposalDto p : report.getPendingProposals()) {
                md.append("| `").append(p.getSourceCode()).append("` | ");
                md.append("`").append(p.getTargetCode()).append("` | ");
                md.append(p.getRelationType()).append(" | ");
                md.append(String.format("%.0f%%", p.getConfidence() * 100)).append(" | ");
                md.append(p.getStatus()).append(" |\n");
            }
            md.append("\n");
        }

        // Mermaid Diagram
        if (report.getMermaidDiagram() != null && !report.getMermaidDiagram().isBlank()) {
            md.append("## ").append(report.getPendingProposals().isEmpty() ? "5" : "6")
              .append(". Architecture Diagram\n\n");
            md.append("```mermaid\n");
            md.append(report.getMermaidDiagram());
            if (!report.getMermaidDiagram().endsWith("\n")) {
                md.append("\n");
            }
            md.append("```\n\n");
        }

        md.append("---\n*Generated by Taxonomy Architecture Analyzer*\n");

        return md.toString();
    }

    /**
     * Renders an {@link ArchitectureReport} as a self-contained HTML document.
     */
    public String renderHtml(ArchitectureReport report) {
        String markdown = renderMarkdown(report);
        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        html.append("  <meta charset=\"UTF-8\">\n");
        html.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("  <title>Architecture Analysis Report</title>\n");
        html.append("  <style>\n");
        html.append("    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; ");
        html.append("max-width: 960px; margin: 2rem auto; padding: 0 1rem; line-height: 1.6; color: #333; }\n");
        html.append("    h1 { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 0.5rem; }\n");
        html.append("    h2 { color: #34495e; margin-top: 2rem; }\n");
        html.append("    h3 { color: #7f8c8d; }\n");
        html.append("    table { border-collapse: collapse; width: 100%; margin: 1rem 0; }\n");
        html.append("    th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }\n");
        html.append("    th { background-color: #f8f9fa; font-weight: 600; }\n");
        html.append("    tr:nth-child(even) { background-color: #f2f2f2; }\n");
        html.append("    code { background: #f4f4f4; padding: 2px 6px; border-radius: 3px; font-size: 0.9em; }\n");
        html.append("    pre { background: #f8f9fa; padding: 1rem; border-radius: 4px; overflow-x: auto; }\n");
        html.append("    .mermaid { background: #fff; padding: 1rem; border: 1px solid #eee; border-radius: 4px; }\n");
        html.append("    hr { border: none; border-top: 1px solid #eee; margin: 2rem 0; }\n");
        html.append("    strong { color: #2c3e50; }\n");
        html.append("  </style>\n");
        html.append("</head>\n<body>\n");

        // Convert markdown to simple HTML
        html.append(markdownToHtml(markdown));

        html.append("\n</body>\n</html>\n");

        return html.toString();
    }

    /**
     * Renders an {@link ArchitectureReport} as a DOCX byte array.
     * Uses Apache POI which is already a project dependency.
     */
    public byte[] renderDocx(ArchitectureReport report) {
        String markdown = renderMarkdown(report);

        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument()) {

            // Title
            org.apache.poi.xwpf.usermodel.XWPFParagraph title = doc.createParagraph();
            title.setStyle("Title");
            org.apache.poi.xwpf.usermodel.XWPFRun titleRun = title.createRun();
            titleRun.setText("Architecture Analysis Report");
            titleRun.setBold(true);
            titleRun.setFontSize(18);

            // Parse markdown lines and add as paragraphs
            for (String line : markdown.split("\n")) {
                if (line.startsWith("# ") && !line.startsWith("# Architecture")) {
                    // Skip duplicate title
                    continue;
                }

                org.apache.poi.xwpf.usermodel.XWPFParagraph para = doc.createParagraph();
                org.apache.poi.xwpf.usermodel.XWPFRun run = para.createRun();

                if (line.startsWith("## ")) {
                    run.setText(line.substring(3));
                    run.setBold(true);
                    run.setFontSize(14);
                } else if (line.startsWith("### ")) {
                    run.setText(line.substring(4));
                    run.setBold(true);
                    run.setFontSize(12);
                } else if (line.startsWith("**") && line.contains(":**")) {
                    // Bold label
                    String text = line.replaceAll("\\*\\*", "");
                    run.setText(text);
                    run.setBold(true);
                } else if (line.startsWith("| ") && !line.startsWith("|---")) {
                    // Table row rendered as tab-separated line
                    String text = line.replaceAll("\\|", "\t").replaceAll("`", "").trim();
                    run.setText(text);
                    run.setFontSize(9);
                } else if (line.startsWith("- **")) {
                    String text = line.replaceAll("\\*\\*", "").substring(2);
                    run.setText(text);
                } else if (line.startsWith("```") || line.startsWith("---") || line.startsWith("|---")) {
                    // Skip formatting lines
                    continue;
                } else {
                    run.setText(line.replaceAll("`", "").replaceAll("\\*", ""));
                }
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            doc.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("DOCX generation failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate DOCX report", e);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private String formatTimestamp(Instant instant) {
        if (instant == null) return "N/A";
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                .withZone(ZoneOffset.UTC)
                .format(instant);
    }

    /**
     * Simple markdown-to-HTML converter for report rendering.
     * Handles headings, tables, bold text, code, and lists.
     */
    String markdownToHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        boolean inTable = false;
        boolean inCodeBlock = false;

        for (String line : markdown.split("\n")) {
            if (line.startsWith("```")) {
                if (inCodeBlock) {
                    html.append("</pre>\n");
                    inCodeBlock = false;
                } else {
                    String lang = line.length() > 3 ? line.substring(3) : "";
                    html.append("<pre class=\"").append(escapeHtml(lang)).append("\">\n");
                    inCodeBlock = true;
                }
                continue;
            }
            if (inCodeBlock) {
                html.append(escapeHtml(line)).append("\n");
                continue;
            }

            // Close table if no longer in table row
            if (inTable && !line.startsWith("|")) {
                html.append("</tbody></table>\n");
                inTable = false;
            }

            if (line.startsWith("# ")) {
                html.append("<h1>").append(inlineFormat(line.substring(2))).append("</h1>\n");
            } else if (line.startsWith("## ")) {
                html.append("<h2>").append(inlineFormat(line.substring(3))).append("</h2>\n");
            } else if (line.startsWith("### ")) {
                html.append("<h3>").append(inlineFormat(line.substring(4))).append("</h3>\n");
            } else if (line.startsWith("|---") || line.startsWith("| ---")) {
                // Skip separator row
            } else if (line.startsWith("| ")) {
                String[] cells = line.split("\\|");
                if (!inTable) {
                    html.append("<table><thead><tr>");
                    for (int i = 1; i < cells.length; i++) {
                        html.append("<th>").append(inlineFormat(cells[i].trim())).append("</th>");
                    }
                    html.append("</tr></thead><tbody>\n");
                    inTable = true;
                } else {
                    html.append("<tr>");
                    for (int i = 1; i < cells.length; i++) {
                        html.append("<td>").append(inlineFormat(cells[i].trim())).append("</td>");
                    }
                    html.append("</tr>\n");
                }
            } else if (line.startsWith("- ")) {
                html.append("<li>").append(inlineFormat(line.substring(2))).append("</li>\n");
            } else if (line.startsWith("---")) {
                html.append("<hr>\n");
            } else if (line.startsWith("*") && line.endsWith("*")) {
                html.append("<p><em>").append(inlineFormat(line.substring(1, line.length() - 1)))
                    .append("</em></p>\n");
            } else if (!line.isBlank()) {
                html.append("<p>").append(inlineFormat(line)).append("</p>\n");
            }
        }

        if (inTable) {
            html.append("</tbody></table>\n");
        }
        if (inCodeBlock) {
            html.append("</pre>\n");
        }

        return html.toString();
    }

    private String inlineFormat(String text) {
        // Bold
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        // Inline code
        text = text.replaceAll("`([^`]+)`", "<code>$1</code>");
        return text;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
