package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.decision.DecisionChapterDiagramRenderer.DiagramPanel;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ChildDecision;
import com.taxonomy.architecture.decision.DecisionRationaleReport.DecisionChapter;
import com.taxonomy.architecture.decision.DecisionRationaleReport.LeafCandidate;
import com.taxonomy.architecture.decision.DecisionRationaleReport.PathStep;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Produces a self-contained, print-ready HTML decision report. */
@Component
@SuppressWarnings("serial")
public class DecisionRationaleHtmlRenderer implements ReportRendererExtension {

    private static final ReportFormatDescriptor DESCRIPTOR = new ReportFormatDescriptor(
            "html", "HTML", "html", "text/html; charset=UTF-8", false);

    private final DecisionChapterDiagramRenderer diagramRenderer;

    public DecisionRationaleHtmlRenderer(DecisionChapterDiagramRenderer diagramRenderer) {
        this.diagramRenderer = diagramRenderer;
    }

    @Override
    public String reportTypeId() {
        return DecisionRationaleReportPlugin.REPORT_TYPE_ID;
    }

    @Override
    public Class<?> reportModelType() {
        return DecisionRationaleReport.class;
    }

    @Override
    public ReportFormatDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ReportRenderResult render(ReportRenderContext context) {
        return new ReportRenderResult(render(
                context.payloadAs(DecisionRationaleReport.class))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String render(DecisionRationaleReport report) {
        DecisionReportLabels labels = new DecisionReportLabels(report.languageTag());
        StringBuilder html = new StringBuilder(64_000);
        html.append("<!doctype html><html lang=\"")
                .append(attr(report.languageTag())).append("\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(text(report.title())).append("</title>")
                .append(styles(report, labels))
                .append("</head><body>");

        html.append("<footer class=\"running-footer\"><span>")
                .append(text(footerText(report, labels)))
                .append("</span><span class=\"page-counter\"></span></footer>");

        renderTitlePage(html, report, labels);
        renderExecutiveSummary(html, report, labels);
        renderChapters(html, report, labels);
        renderAppendix(html, report, labels);
        html.append("</body></html>");
        return html.toString();
    }

    private void renderTitlePage(
            StringBuilder html,
            DecisionRationaleReport report,
            DecisionReportLabels labels) {
        DecisionRationaleReport.ReportMetadata metadata = report.metadata();
        html.append("<section class=\"title-page\">")
                .append("<div class=\"brand-rule\"></div>")
                .append("<div class=\"eyebrow\">TAXONOMY · DECISION EVIDENCE</div>")
                .append("<h1>").append(text(report.title())).append("</h1>")
                .append("<p class=\"subtitle\">").append(text(labels.subtitle())).append("</p>")
                .append("<div class=\"status status-")
                .append(attr(report.status().name().toLowerCase(Locale.ROOT)))
                .append("\">").append(text(statusLabel(report, labels))).append("</div>")
                .append("<div class=\"requirement-card\"><h2>")
                .append(text(labels.requirement())).append("</h2><p>")
                .append(text(report.requirement())).append("</p></div>")
                .append("<div class=\"metadata-grid\">");
        metadataItem(html, labels.dataVersion(), metadata.taxonomyDataVersion());
        metadataItem(html, labels.sourceFile(), metadata.taxonomyCatalogueFile());
        metadataItem(html, labels.dataDigest(), shortHash(metadata.taxonomyDataFingerprintSha256()));
        metadataItem(html, labels.hierarchyEvidence(), metadata.hierarchyFromImmutableSnapshot()
                ? labels.frozenHierarchy() : labels.liveHierarchy());
        metadataItem(html, labels.nodes(), metadata.taxonomyNodeCount() + " / " + metadata.taxonomyRootCount());
        metadataItem(html, labels.analysisSnapshot(), metadata.analysisSnapshotId());
        metadataItem(html, labels.requirementVersion(), requirementVersion(report));
        metadataItem(html, labels.analysisProvider(), metadata.analysisProvider());
        metadataItem(html, labels.analysisModel(), metadata.analysisModel());
        metadataItem(html, labels.analysisCreatedAt(),
                formatInstant(metadata.analysisCreatedAt(), metadata.reportTimeZone()));
        metadataItem(html, labels.analysisCreatedBy(), metadata.analysisCreatedBy());
        metadataItem(html, labels.generatedAt(), formatInstant(metadata.generatedAt(), metadata.reportTimeZone()));
        metadataItem(html, labels.generatedBy(), metadata.generatedBy());
        metadataItem(html, labels.applicationVersion(), metadata.taxonomyApplicationVersion());
        metadataItem(html, labels.basedOnCommit(), shortHash(metadata.basedOnCommit()));
        html.append("</div><div class=\"classification-note\">")
                .append(text(labels.methodology())).append(": ")
                .append(text(report.executiveSummary().methodologyNote()))
                .append("</div></section>");
    }

    private void renderExecutiveSummary(
            StringBuilder html,
            DecisionRationaleReport report,
            DecisionReportLabels labels) {
        html.append("<section class=\"report-section executive\"><div class=\"section-kicker\">01</div><h2>")
                .append(text(labels.executiveSummary())).append("</h2>")
                .append("<p class=\"lead\">")
                .append(text(report.executiveSummary().conciseConclusion())).append("</p>");

        LeafCandidate leading = report.executiveSummary().leadingLeaf();
        if (leading != null) {
            html.append("<div class=\"leading-leaf\"><div><span class=\"small-label\">")
                    .append(text(labels.leadingLeaf())).append("</span><strong>")
                    .append(text(leading.code())).append(" · ").append(text(leading.title()))
                    .append("</strong><span>").append(text(leading.taxonomyRoot()))
                    .append("</span></div><div class=\"score-orb\">")
                    .append(leading.score()).append("<small>%</small></div></div>");
        }

        html.append("<h3>").append(text(labels.highestPath())).append("</h3>")
                .append("<table class=\"path-table\"><thead><tr><th>")
                .append(text(labels.step())).append("</th><th>")
                .append(text(labels.node())).append("</th><th>")
                .append(text(labels.score())).append("</th><th>")
                .append(text(labels.localShare())).append("</th><th>")
                .append(text(labels.rationale())).append("</th><th>")
                .append(text(labels.reasonSource())).append("</th></tr></thead><tbody>");
        for (PathStep step : report.executiveSummary().path()) {
            html.append("<tr><td>").append(step.position()).append("</td><td><code>")
                    .append(text(step.code())).append("</code><br><span class=\"muted\">")
                    .append(text(step.title())).append("</span></td><td>")
                    .append(score(step.absoluteScore())).append("</td><td>")
                    .append(percent(step.localSharePercent())).append("</td><td>")
                    .append(text(step.reason())).append("</td><td>")
                    .append(text(reasonSource(step.reasonSource(), labels))).append("</td></tr>");
        }
        html.append("</tbody></table>");

        if (!report.warnings().isEmpty()) {
            html.append("<div class=\"warning-box\"><h3>")
                    .append(text(labels.warnings())).append("</h3><ul>");
            report.warnings().forEach(warning -> html.append("<li>")
                    .append(text(warning)).append("</li>"));
            html.append("</ul></div>");
        }
        html.append("</section>");
    }

    private void renderChapters(
            StringBuilder html,
            DecisionRationaleReport report,
            DecisionReportLabels labels) {
        html.append("<section class=\"report-section chapter-intro\"><div class=\"section-kicker\">02</div><h2>")
                .append(text(labels.decisionChapters())).append("</h2><p>")
                .append(text(labels.german()
                        ? "Jedes folgende Kapitel entspricht genau einem Vaterknoten, dessen direkte Kindknoten mindestens einen von Null verschiedenen Bewertungswert enthalten."
                        : "Each following chapter corresponds to one parent node whose direct children contain at least one non-zero score."))
                .append("</p></section>");

        for (DecisionChapter chapter : report.chapters()) {
            html.append("<section class=\"report-section decision-chapter\"><div class=\"chapter-number\">")
                    .append(chapter.number()).append("</div><div class=\"chapter-heading\"><div><span class=\"small-label\">")
                    .append(text(labels.parentNode())).append("</span><h2><code>")
                    .append(text(chapter.parentCode())).append("</code> · ")
                    .append(text(chapter.parentTitle())).append("</h2></div><div class=\"parent-score\">")
                    .append(score(chapter.parentScore())).append("</div></div>");
            if (chapter.parentDescription() != null && !chapter.parentDescription().isBlank()) {
                html.append("<p class=\"parent-description\">")
                        .append(text(chapter.parentDescription())).append("</p>");
            }

            List<DiagramPanel> panels = diagramRenderer.render(chapter, report.languageTag());
            for (DiagramPanel panel : panels) {
                html.append("<figure class=\"decision-diagram\">")
                        .append(panel.svg())
                        .append("<figcaption>")
                        .append(text(labels.parentNode() + " " + chapter.parentCode()))
                        .append(panel.panelCount() > 1
                                ? " · " + panel.panelNumber() + "/" + panel.panelCount() : "")
                        .append("</figcaption></figure>");
            }

            html.append("<div class=\"rationale-grid\"><div><h3>")
                    .append(text(labels.decisionResult())).append("</h3><p>")
                    .append(text(chapter.decisionSummary())).append("</p></div><div><h3>")
                    .append(text(labels.comparison())).append("</h3><p>")
                    .append(text(chapter.comparativeRationale())).append("</p></div></div>")
                    .append("<h3>").append(text(labels.alternatives())).append("</h3>")
                    .append("<table><thead><tr><th>").append(text(labels.rank()))
                    .append("</th><th>").append(text(labels.node()))
                    .append("</th><th>").append(text(labels.score()))
                    .append("</th><th>").append(text(labels.localShare()))
                    .append("</th><th>").append(text(labels.disposition()))
                    .append("</th><th>").append(text(labels.rationale()))
                    .append("</th><th>").append(text(labels.reasonSource()))
                    .append("</th></tr></thead><tbody>");
            for (ChildDecision child : chapter.children()) {
                html.append("<tr class=\"").append(rowClass(child)).append("\"><td>")
                        .append(child.rank() == null ? "—" : child.rank()).append("</td><td><code>")
                        .append(text(child.code())).append("</code><br><span class=\"muted\">")
                        .append(text(child.title())).append("</span></td><td>")
                        .append(score(child.absoluteScore())).append("</td><td>")
                        .append(percent(child.localSharePercent())).append("</td><td>")
                        .append(text(disposition(child, labels))).append("</td><td>")
                        .append(text(child.reason())).append("</td><td>")
                        .append(text(reasonSource(child.reasonSource(), labels))).append("</td></tr>");
            }
            html.append("</tbody></table>");
            if (!chapter.complete()) {
                html.append("<div class=\"warning-box compact\">")
                        .append(text(labels.german()
                                ? "Nicht bewertete direkte Kinder: "
                                : "Unevaluated direct children: "))
                        .append(text(String.join(", ", chapter.missingChildCodes())))
                        .append("</div>");
            }
            html.append("</section>");
        }
    }

    private void renderAppendix(
            StringBuilder html,
            DecisionRationaleReport report,
            DecisionReportLabels labels) {
        html.append("<section class=\"report-section appendix\"><div class=\"section-kicker\">03</div><h2>")
                .append(text(labels.appendix())).append("</h2><h3>")
                .append(text(labels.leadingLeaves())).append("</h3>")
                .append("<table><thead><tr><th>").append(text(labels.rank()))
                .append("</th><th>").append(text(labels.node()))
                .append("</th><th>").append(text(labels.score()))
                .append("</th><th>").append(text(labels.taxonomyRoot()))
                .append("</th><th>").append(text(labels.hierarchyPath()))
                .append("</th><th>").append(text(labels.rationale()))
                .append("</th></tr></thead><tbody>");
        int rank = 1;
        for (LeafCandidate leaf : report.leadingLeaves()) {
            html.append("<tr><td>").append(rank++).append("</td><td><code>")
                    .append(text(leaf.code())).append("</code><br><span class=\"muted\">")
                    .append(text(leaf.title())).append("</span></td><td>")
                    .append(leaf.score()).append(" %</td><td>")
                    .append(text(leaf.taxonomyRoot())).append("</td><td>")
                    .append(text(leaf.hierarchyPath())).append("</td><td>")
                    .append(text(leaf.reason())).append("</td></tr>");
        }
        html.append("</tbody></table><h3>").append(text(labels.taxonomyBasis()))
                .append("</h3><dl class=\"evidence-list\">");
        evidence(html, labels.catalogue(), report.metadata().taxonomyDataSource());
        evidence(html, labels.sourceFile(), report.metadata().taxonomyCatalogueFile());
        evidence(html, labels.sourceDigest(), report.metadata().taxonomyCatalogueResourceSha256());
        evidence(html, labels.dataDigest(), report.metadata().taxonomyDataFingerprintSha256());
        evidence(html, labels.recordedTaxonomyDigest(),
                report.metadata().recordedTaxonomyFingerprintSha256());
        evidence(html, labels.promptDigest(), report.metadata().promptFingerprintSha256());
        evidence(html, labels.analysisDigest(), report.metadata().analysisSnapshotFingerprintSha256());
        evidence(html, labels.analysisSnapshot(), report.metadata().analysisSnapshotId());
        evidence(html, labels.requirementVersion(), requirementVersion(report));
        evidence(html, labels.analysisCreatedAt(),
                formatInstant(report.metadata().analysisCreatedAt(), report.metadata().reportTimeZone()));
        evidence(html, labels.analysisCreatedBy(), report.metadata().analysisCreatedBy());
        evidence(html, labels.repository(), report.metadata().repositoryId());
        evidence(html, labels.workspace(), report.metadata().workspaceId());
        evidence(html, labels.branch(), report.metadata().branch());
        evidence(html, labels.basedOnCommit(), report.metadata().basedOnCommit());
        evidence(html, labels.analysisProvider(), report.metadata().analysisProvider());
        evidence(html, labels.analysisModel(), report.metadata().analysisModel());
        evidence(html, labels.analysisStatus(), report.metadata().analysisStatus());
        html.append("</dl><h3>").append(text(labels.methodology())).append("</h3><p>")
                .append(text(report.executiveSummary().methodologyNote())).append("</p>");
        if (!report.discrepancies().isEmpty()) {
            html.append("<h3>").append(text(labels.german()
                    ? "Dokumentierte Bewertungsabweichungen"
                    : "Documented scoring discrepancies"))
                    .append("</h3><pre>")
                    .append(text(report.discrepancies().toString())).append("</pre>");
        }
        html.append("</section>");
    }

    private String styles(DecisionRationaleReport report, DecisionReportLabels labels) {
        String pageCounter = "\"" + cssString(labels.page() + " ")
                + "\" counter(page) \" " + cssString(labels.of())
                + " \" counter(pages)";
        return """
                <style>
                :root{--navy:#0b1f33;--teal:#007a78;--teal-light:#e1f4f3;--blue-light:#e9f0f7;--ink:#26303a;--muted:#64707c;--line:#cdd5dc;--paper:#fff;--warning:#fff4d6;--warning-border:#b98200}
                *{box-sizing:border-box}html{background:#eef1f4}body{margin:0 auto;max-width:1120px;background:var(--paper);color:var(--ink);font:16px/1.5 "Segoe UI",Arial,sans-serif;box-shadow:0 0 30px rgba(11,31,51,.12)}
                .running-footer{position:fixed;left:0;right:0;bottom:0;height:34px;padding:8px 18mm;border-top:1px solid var(--line);background:#fff;color:var(--muted);font-size:10px;display:flex;justify-content:space-between;z-index:20}.page-counter:after{content:""}
                .title-page,.report-section{padding:20mm 18mm 24mm}.title-page{min-height:285mm;position:relative;page-break-after:always;background:linear-gradient(150deg,#fff 0,#fff 70%,#edf7f6 100%)}
                .brand-rule{height:8px;background:linear-gradient(90deg,var(--navy) 0 72%,var(--teal) 72%);margin-bottom:36px}.eyebrow,.section-kicker,.small-label{color:var(--teal);font-weight:700;letter-spacing:.12em;text-transform:uppercase;font-size:12px}
                h1{font-size:44px;line-height:1.12;color:var(--navy);max-width:800px;margin:18px 0}.subtitle{font-size:21px;color:var(--muted);max-width:760px}.status{display:inline-block;margin-top:18px;padding:8px 14px;border-radius:999px;font-weight:700;background:var(--teal-light);color:var(--teal)}.status-draft_incomplete,.status-no_result{background:var(--warning);color:#745400}
                .requirement-card{margin-top:42px;padding:20px 24px;border-left:6px solid var(--teal);background:#f6f8fa}.requirement-card h2{margin:0 0 8px;font-size:15px;color:var(--teal);text-transform:uppercase;letter-spacing:.08em}.requirement-card p{font-size:20px;margin:0;white-space:pre-wrap}
                .metadata-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:1px;background:var(--line);border:1px solid var(--line);margin-top:30px}.metadata-item{background:#fff;padding:13px 16px}.metadata-item span{display:block;color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.05em}.metadata-item strong{display:block;margin-top:3px;overflow-wrap:anywhere}.classification-note{position:absolute;left:18mm;right:18mm;bottom:22mm;color:var(--muted);font-size:11px}
                .report-section{page-break-before:auto}.decision-chapter{page-break-before:always}.section-kicker{margin-bottom:5px}.report-section>h2{font-size:32px;color:var(--navy);margin:0 0 20px}.report-section h3{color:var(--navy);margin:26px 0 10px}.lead{font-size:20px;color:var(--navy)}
                .leading-leaf{display:flex;justify-content:space-between;align-items:center;background:var(--navy);color:#fff;padding:22px 26px;border-radius:8px;margin:24px 0}.leading-leaf strong,.leading-leaf span{display:block}.leading-leaf .small-label{color:#8de0dd}.score-orb{width:92px;height:92px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:var(--teal);font-size:35px;font-weight:800}.score-orb small{font-size:18px;margin-left:2px}
                table{width:100%;border-collapse:collapse;margin:12px 0 24px;font-size:13px;page-break-inside:auto}thead{display:table-header-group}tr{page-break-inside:avoid}th{background:var(--navy);color:#fff;text-align:left;padding:9px 8px;font-weight:650}td{border-bottom:1px solid var(--line);padding:9px 8px;vertical-align:top}tbody tr:nth-child(even){background:#f7f9fa}code{font-family:"Cascadia Mono",Consolas,monospace;color:var(--navy);font-weight:700}.muted{color:var(--muted)}
                .warning-box{background:var(--warning);border-left:5px solid var(--warning-border);padding:14px 18px;margin:22px 0}.warning-box h3{margin-top:0}.warning-box.compact{font-size:13px}.chapter-number{color:var(--teal);font-weight:800;font-size:14px}.chapter-heading{display:flex;align-items:flex-start;justify-content:space-between;border-bottom:3px solid var(--navy);padding-bottom:12px}.chapter-heading h2{margin:4px 0 0;color:var(--navy);font-size:29px}.parent-score{background:var(--teal);color:#fff;padding:10px 16px;border-radius:6px;font-weight:800;font-size:24px;white-space:nowrap}.parent-description{color:var(--muted)}
                .decision-diagram{margin:24px 0;page-break-inside:avoid}.decision-diagram svg{width:100%;height:auto;border:1px solid var(--line);border-radius:6px;background:#fff}.decision-diagram figcaption{font-size:11px;color:var(--muted);text-align:right}.rationale-grid{display:grid;grid-template-columns:1fr 1.3fr;gap:22px}.rationale-grid>div{background:#f6f8fa;padding:14px 18px;border-top:4px solid var(--teal)}.rationale-grid h3{margin-top:0}.row-leading{background:var(--teal-light)!important}.row-rejected{color:var(--muted)}.row-missing{font-style:italic;color:var(--muted)}
                .evidence-list{display:grid;grid-template-columns:210px 1fr;border-top:1px solid var(--line)}.evidence-list dt,.evidence-list dd{margin:0;padding:8px;border-bottom:1px solid var(--line)}.evidence-list dt{font-weight:700;color:var(--navy)}.evidence-list dd{overflow-wrap:anywhere}pre{white-space:pre-wrap;background:#f6f8fa;padding:12px;border:1px solid var(--line)}
                @media(max-width:760px){body{box-shadow:none}.title-page,.report-section{padding:28px 20px}.metadata-grid,.rationale-grid{grid-template-columns:1fr}.chapter-heading{display:block}.parent-score{display:inline-block;margin-top:12px}.running-footer{display:none}}
                @media print{html,body{background:#fff;box-shadow:none;max-width:none}.running-footer{position:fixed}.page-counter:after{content:PAGE_COUNTER}@page{size:A4;margin:14mm 12mm 18mm}.title-page,.report-section{padding:12mm 10mm 18mm}.title-page{min-height:255mm}.classification-note{left:10mm;right:10mm;bottom:16mm}}
                </style>
                """.replace("PAGE_COUNTER", pageCounter);
    }

    private void metadataItem(StringBuilder html, String label, String value) {
        html.append("<div class=\"metadata-item\"><span>").append(text(label))
                .append("</span><strong>").append(text(value)).append("</strong></div>");
    }

    private void evidence(StringBuilder html, String label, String value) {
        html.append("<dt>").append(text(label)).append("</dt><dd>")
                .append(text(value)).append("</dd>");
    }

    private String statusLabel(DecisionRationaleReport report, DecisionReportLabels labels) {
        return switch (report.status()) {
            case FINAL -> labels.finalStatus();
            case FINAL_WITH_WARNINGS -> labels.finalWarnings();
            case DRAFT_INCOMPLETE -> labels.draft();
            case NO_RESULT -> labels.noResult();
        };
    }

    private String disposition(ChildDecision child, DecisionReportLabels labels) {
        return switch (child.disposition()) {
            case CONTINUED -> labels.continued();
            case LEAF_CANDIDATE -> labels.leafCandidate();
            case REJECTED -> labels.rejected();
            case NOT_EVALUATED -> labels.notEvaluated();
        };
    }

    private String reasonSource(
            DecisionRationaleReport.ReasonSource source,
            DecisionReportLabels labels) {
        if (source == null) {
            return labels.missingReason();
        }
        return switch (source) {
            case AI_SCORING -> labels.aiReason();
            case DETERMINISTIC_TRACE -> labels.deterministicReason();
            case MISSING -> labels.missingReason();
        };
    }

    private String rowClass(ChildDecision child) {
        if (child.leadingSibling()) {
            return "row-leading";
        }
        return switch (child.disposition()) {
            case REJECTED -> "row-rejected";
            case NOT_EVALUATED -> "row-missing";
            default -> "";
        };
    }

    private String requirementVersion(DecisionRationaleReport report) {
        if (report.metadata().requirementVersionNumber() == null) {
            return "—";
        }
        StringBuilder value = new StringBuilder("v")
                .append(report.metadata().requirementVersionNumber());
        if (report.metadata().requirementId() != null) {
            value.append(" · requirement ").append(report.metadata().requirementId());
        }
        if (report.metadata().projectId() != null) {
            value.append(" · project ").append(report.metadata().projectId());
        }
        return value.toString();
    }

    private String footerText(DecisionRationaleReport report, DecisionReportLabels labels) {
        return labels.generatedNotice() + " · "
                + formatInstant(report.metadata().generatedAt(), report.metadata().reportTimeZone())
                + " · " + report.metadata().generatedBy()
                + " · Taxonomy " + report.metadata().taxonomyApplicationVersion()
                + " · " + report.metadata().taxonomyDataVersion();
    }

    private String formatInstant(Instant instant, String zone) {
        if (instant == null) {
            return "—";
        }
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (Exception ignored) {
            zoneId = ZoneOffset.UTC;
        }
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z", Locale.GERMANY)
                .withZone(zoneId)
                .format(instant);
    }

    private String score(Integer value) {
        return value == null ? "—" : value + " %";
    }

    private String percent(Double value) {
        if (value == null) {
            return "—";
        }
        return Math.abs(value - Math.rint(value)) < 0.05
                ? String.format(Locale.ROOT, "%.0f %%", value)
                : String.format(Locale.ROOT, "%.1f %%", value);
    }

    private String shortHash(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return value.length() <= 16 ? value : value.substring(0, 16) + "…";
    }

    private String text(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String cssString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\A " );
    }

    private String attr(String value) {
        return text(value);
    }
}
