package com.taxonomy.reporting.render.document;

import com.taxonomy.dto.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Format-only rendering of an already captured architecture report. */
@Component
public final class ArchitectureReportTextRenderer {
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
                    md.append("| ").append(tableCode(anchor.getNodeCode())).append(" | ");
                    md.append(anchor.getDirectScore()).append("% | ");
                    md.append(tableText(anchor.getReason())).append(" |\n");
                }
                md.append("\n");
            }
        }

        if (view != null && !view.getIncludedRelationships().isEmpty()) {
            md.append("### Included Relationships\n\n");
            md.append("| ID | Source | Target | Type | Category | Reasons |\n|---|---|---|---|---|---|\n");
            for (RequirementRelationshipView relation : view.getIncludedRelationships()) {
                md.append("| ").append(relation.getRelationId() == null ? "—" : relation.getRelationId())
                        .append(" | ").append(tableText(relation.getSourceCode()))
                        .append(" | ").append(tableText(relation.getTargetCode()))
                        .append(" | ").append(tableText(relation.getRelationType()))
                        .append(" | ").append(tableText(relation.getRelationCategory()))
                        .append(" | ").append(tableText(relationshipReasons(relation))).append(" |\n");
            }
            md.append("\n");
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
                    md.append("| ").append(tableCode(mr.getSourceNodeCode())).append(" | ");
                    md.append(tableText(mr.getExpectedRelationType())).append(" | ");
                    md.append(tableText(mr.getExpectedTargetRoot())).append(" | ");
                    md.append(tableText(mr.getDescription())).append(" |\n");
                }
                md.append("\n");
            }

            if (!gaps.getIncompletePatterns().isEmpty()) {
                md.append("### Incomplete Patterns\n\n");
                md.append("| Node | Pattern | Missing |\n|---|---|---|\n");
                for (IncompletePattern ip : gaps.getIncompletePatterns()) {
                    md.append("| ").append(tableCode(ip.getNodeCode())).append(" | ");
                    md.append(tableText(ip.getPatternDescription())).append(" | ");
                    md.append(tableText(ip.getMissingElement())).append(" |\n");
                }
                md.append("\n");
            }
        }

        // Pattern Detection
        md.append("## 3. Detected Patterns\n\n");
        PatternDetectionView patterns = report.getPatternDetection();
        if (patterns != null) {
            md.append("**Pattern Coverage:** ").append(
                    formatBoundedPercentage(patterns.getPatternCoverage(), 1)).append("\n\n");

            if (!patterns.getMatchedPatterns().isEmpty()) {
                md.append("### Matched Patterns\n\n");
                for (DetectedPattern dp : patterns.getMatchedPatterns()) {
                    md.append("- **").append(dp.getPatternName()).append("** — ");
                    md.append(formatBoundedPercentage(dp.getCompleteness(), 0)).append(" complete");
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
                    md.append(formatBoundedPercentage(dp.getCompleteness(), 0)).append(" complete, missing: ");
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

            rec.getReasoning().forEach(reason -> md.append(reason).append("\n\n"));
            rec.getNotes().forEach(note -> md.append(note).append("\n\n"));

            if (!rec.getConfirmedElements().isEmpty()) {
                md.append("### Matched Elements\n\n");
                md.append("High-score matches; human approval is not recorded.\n\n");
                md.append("| Node | Title | Root | Score | Reasoning |\n|---|---|---|---|---|\n");
                for (RecommendedElement el : rec.getConfirmedElements()) {
                    md.append("| ").append(tableCode(el.getNodeCode())).append(" | ");
                    md.append(tableText(el.getTitle())).append(" | ");
                    md.append(tableText(el.getTaxonomyRoot())).append(" | ");
                    md.append(el.getScore()).append("% | ");
                    md.append(tableText(el.getReasoning())).append(" |\n");
                }
                md.append("\n");
            }

            if (!rec.getProposedElements().isEmpty()) {
                md.append("### Proposed Elements\n\n");
                md.append("| Node | Title | Root | Score | Reasoning |\n|---|---|---|---|---|\n");
                for (RecommendedElement el : rec.getProposedElements()) {
                    md.append("| ").append(tableCode(el.getNodeCode())).append(" | ");
                    md.append(tableText(el.getTitle())).append(" | ");
                    md.append(tableText(el.getTaxonomyRoot())).append(" | ");
                    md.append(el.getScore()).append("% | ");
                    md.append(tableText(el.getReasoning())).append(" |\n");
                }
                md.append("\n");
            }

            if (!rec.getSuggestedRelations().isEmpty()) {
                md.append("### Suggested Relations\n\n");
                md.append("| Source | → | Target | Type | Reasoning |\n|---|---|---|---|---|\n");
                for (SuggestedRelation sr : rec.getSuggestedRelations()) {
                    md.append("| ").append(tableCode(sr.getSourceCode())).append(" | → | ");
                    md.append(tableCode(sr.getTargetCode())).append(" | ");
                    md.append(tableText(sr.getRelationType())).append(" | ");
                    md.append(tableText(sr.getReasoning())).append(" |\n");
                }
                md.append("\n");
            }
        }

        // Pending Proposals
        if (!report.getPendingProposals().isEmpty()) {
            md.append("## 5. Pending Relation Proposals\n\n");
            md.append("| ID | Source | Target | Type | Confidence | Status | Reasons |\n|---|---|---|---|---|---|---|\n");
            for (RelationProposalDto p : report.getPendingProposals()) {
                md.append("| ").append(p.getId() == null ? "—" : p.getId()).append(" | ");
                md.append(tableCode(p.getSourceCode())).append(" | ");
                md.append(tableCode(p.getTargetCode())).append(" | ");
                md.append(tableText(p.getRelationType())).append(" | ");
                md.append(AnalysisPercentages.display(p.getConfidence() * 100)).append(" | ");
                md.append(tableText(p.getStatus())).append(" | ");
                md.append(tableText(proposalReasons(p))).append(" |\n");
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
        return new com.taxonomy.reporting.render.document.ArchitectureReportDocxRenderer().render(report);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static String formatBoundedPercentage(double value, int fractionDigits) {
        double bounded = Double.isFinite(value)
                ? Math.max(0.0, Math.min(100.0, value))
                : 0.0;
        return switch (fractionDigits) {
            case 0 -> String.format(Locale.ROOT, "%.0f%%", bounded);
            case 1 -> String.format(Locale.ROOT, "%.1f%%", bounded);
            default -> throw new IllegalArgumentException(
                    "Unsupported percentage precision: " + fractionDigits);
        };
    }

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
                List<String> cells = tableCells(line);
                if (!inTable) {
                    html.append("<table><thead><tr>");
                    for (String cell : cells) {
                        html.append("<th>").append(inlineFormat(cell.trim())).append("</th>");
                    }
                    html.append("</tr></thead><tbody>\n");
                    inTable = true;
                } else {
                    html.append("<tr>");
                    for (String cell : cells) {
                        html.append("<td>").append(inlineFormat(cell.trim())).append("</td>");
                    }
                    html.append("</tr>\n");
                }
            } else if (line.startsWith("- ")) {
                html.append("<li>").append(inlineFormat(line.substring(2))).append("</li>\n");
            } else if (line.startsWith("---")) {
                html.append("<hr>\n");
            } else if (line.startsWith("*") && !line.startsWith("**") && line.endsWith("*")) {
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
        return inlineFormat(text, true, true);
    }

    /** Render only authored Markdown tokens; all content is escaped at its output boundary. */
    private String inlineFormat(String text, boolean bold, boolean links) {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()
                    && "\\`*[]()|".indexOf(text.charAt(i + 1)) >= 0) {
                html.append(escapeHtml(text.substring(i + 1, i + 2)));
                i += 2;
                continue;
            }
            if (c == '`') {
                int length = 1;
                while (i + length < text.length() && text.charAt(i + length) == '`') length++;
                int end = codeSpanEnd(text, i, length);
                if (end >= 0) {
                    html.append("<code>").append(escapeHtml(codeText(text.substring(i + length, end))))
                            .append("</code>");
                    i = end + length;
                    continue;
                }
                html.append(escapeHtml(text.substring(i, i + length)));
                i += length;
                continue;
            }
            if (bold && text.startsWith("**", i)) {
                int end = text.indexOf("**", i + 2);
                if (end > i + 2) {
                    html.append("<strong>").append(inlineFormat(text.substring(i + 2, end), false, links))
                            .append("</strong>");
                    i = end + 2;
                    continue;
                }
            }
            if (links && c == '[') {
                int labelEnd = text.indexOf("](", i + 1);
                int end = labelEnd < 0 ? -1 : linkEnd(text, labelEnd + 2);
                if (end >= 0) {
                    String destination = text.substring(labelEnd + 2, end);
                    if (safeLink(destination)) {
                        html.append("<a href=\"").append(escapeHtml(destination)).append("\">")
                                .append(inlineFormat(text.substring(i + 1, labelEnd), bold, false))
                                .append("</a>");
                    } else {
                        html.append(escapeHtml(text.substring(i, end + 1)));
                    }
                    i = end + 1;
                    continue;
                }
                // No later complete link is possible; avoid rescanning long literal bracket runs.
                links = false;
            }
            html.append(escapeHtml(String.valueOf(c)));
            i++;
        }
        return html.toString();
    }

    private boolean safeLink(String destination) {
        try {
            var uri = new java.net.URI(destination);
            String scheme = uri.getScheme();
            return scheme != null && Set.of("https", "http", "mailto")
                    .contains(scheme.toLowerCase(Locale.ROOT));
        } catch (java.net.URISyntaxException exception) {
            return false;
        }
    }

    private int linkEnd(String text, int start) {
        int depth = 1;
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '(') depth++;
            else if (text.charAt(i) == ')' && --depth == 0) return i;
        }
        return -1;
    }

    /** Pipes escaped by table authors or inside code spans are content, not separators. */
    private List<String> tableCells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int i = 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                cell.append(c).append(line.charAt(++i));
            } else if (c == '`') {
                int length = 1;
                while (i + length < line.length() && line.charAt(i + length) == '`') length++;
                int end = codeSpanEnd(line, i, length);
                if (end >= 0) {
                    // Table escaping is separate from literal code content, including backslashes.
                    cell.append(line, i, i + length)
                            .append(line.substring(i + length, end).replace("\\|", "|"))
                            .append(line, end, end + length);
                    i = end + length - 1;
                } else {
                    cell.append("`".repeat(length));
                    i += length - 1;
                }
            } else if (c == '|') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        if (!cell.toString().isBlank()) cells.add(cell.toString());
        return cells;
    }

    static String relationshipReasons(RequirementRelationshipView relation) {
        var metadata = new com.taxonomy.diagram.DiagramRelationMetadata(relation.getRelationId(),
                relation.getOrigin(), relation.getRecordedConfidence(), relation.getIncludedBecause(),
                relation.getDerivationReason(), relation.getPresenceReason(), relation.getSeedType(),
                relation.getRequirementEvidence());
        return String.join("; ", com.taxonomy.export.DiagramPresentation.relationshipDetails(metadata));
    }

    static String proposalReasons(RelationProposalDto proposal) {
        List<String> reasons = new ArrayList<>();
        if (proposal.getRationale() != null) reasons.add("Rationale: " + proposal.getRationale());
        if (proposal.getProvenance() != null) reasons.add("Provenance: " + proposal.getProvenance());
        if (proposal.getExplanationBasis() != null) reasons.add("Explanation: " + proposal.getExplanationBasis());
        return String.join("; ", reasons);
    }

    private static String tableCode(String value) {
        String text = normalizedTableText(value).replace("|", "\\|");
        int maximum = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            maximum = Math.max(maximum, run);
        }
        String delimiter = "`".repeat(maximum + 1);
        return delimiter + (text.startsWith("`") || text.endsWith("`") ? " " + text + " " : text) + delimiter;
    }

    private static String codeText(String text) {
        // Padding permits literal backticks at either end of a Markdown code span.
        if (text.startsWith(" ") && text.endsWith(" ") && !text.isBlank())
            return text.substring(1, text.length() - 1);
        return text;
    }

    private static String tableText(String value) {
        String text = normalizedTableText(value);
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '`') {
                int length = 1;
                while (i + length < text.length() && text.charAt(i + length) == '`') length++;
                int end = codeSpanEnd(text, i, length);
                if (end >= 0) {
                    escaped.append(text.substring(i, end + length).replace("|", "\\|"));
                    i = end + length - 1;
                    continue;
                }
                escaped.append("\\`".repeat(length));
                i += length - 1;
                continue;
            }
            if (c == '\\' || c == '|' || c == '`') escaped.append('\\');
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static String normalizedTableText(String value) {
        return value == null ? "—" : value.replace('\n', ' ').replace('\r', ' ');
    }

    /** Code delimiters match an entire backtick run, never part of a longer one. */
    private static int codeSpanEnd(String text, int start, int length) {
        for (int i = start + length; i < text.length(); i++) {
            if (text.charAt(i) != '`') continue;
            int run = 1;
            while (i + run < text.length() && text.charAt(i + run) == '`') run++;
            if (run == length) return i;
            i += run - 1;
        }
        return -1;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
