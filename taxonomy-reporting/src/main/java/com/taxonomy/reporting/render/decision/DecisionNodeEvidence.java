package com.taxonomy.reporting.render.decision;

import com.taxonomy.dto.AnalysisPercentages;
import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionRationaleReport.*;
import com.taxonomy.reporting.api.decision.DecisionReportOptions.Section;
import com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow;
import com.taxonomy.reporting.render.document.WordDocumentWriter;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Document-local navigation over the full captured tree, independent of ranked-leaf limits. */
public final class DecisionNodeEvidence {
    public record Evidence(String text, ReasonSource source) {}
    public record Node(DecisionTreeRow row, String root, List<String> path, Evidence evidence) {}
    private final DecisionRationaleReport report;
    private final LinkedHashMap<String, List<Node>> roots = new LinkedHashMap<>();
    private final Map<String, Node> nodes = new LinkedHashMap<>();

    public DecisionNodeEvidence(DecisionRationaleReport report) {
        this.report = report;
        String root = null;
        var path = new ArrayList<String>();
        for (var row : report.scope().decisionTree().rows()) {
            if (row.depth() == 0 || root == null) { root = row.code(); path.clear(); }
            while (path.size() > row.depth()) path.removeLast();
            path.add(row.code());
            var node = new Node(row, root, List.copyOf(path), evidence(row.code()));
            roots.computeIfAbsent(root, ignored -> new ArrayList<>()).add(node);
            nodes.put(row.code(), node);
        }
        // Older sources may omit an unassessed selected root from their captured rows.
        for (String code : report.scope().reportRoots()) if (!roots.containsKey(code)) {
            String title = report.scope().availableRoots().stream().filter(r -> r.code().equals(code))
                    .map(r -> r.title()).findFirst().orElse(code);
            var row = new DecisionTreeRow(0, code, title, null, Disposition.NOT_EVALUATED, null, null);
            var node = new Node(row, code, List.of(code + " · " + title), evidence(code));
            roots.put(code, List.of(node)); nodes.put(code, node);
        }
    }

    public static String reasonBookmark(String code) { return bookmark("dn_", code); }
    public static String rootBookmark(String code) { return bookmark("dt_", code); }
    private static String bookmark(String prefix, String code) {
        return prefix + UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }
    public Node node(String code) { return nodes.get(code); }
    public boolean matches(String code, String text, ReasonSource source) {
        return nodes.containsKey(code) && nodes.get(code).evidence().equals(new Evidence(text, source));
    }
    private Evidence evidence(String code) {
        String recorded = report.scope().recordedReasons().get(code);
        var saved = report.chapters().stream().flatMap(ch -> ch.children().stream())
                .filter(child -> child.code().equals(code)).findFirst().orElse(null);
        var leaf = report.leadingLeaves().stream().filter(l -> l.code().equals(code)).findFirst().orElse(null);
        var step = report.executiveSummary() == null ? null : report.executiveSummary().path().stream()
                .filter(p -> p.code().equals(code)).findFirst().orElse(null);
        if (recorded != null) {
            ReasonSource source = saved != null && Objects.equals(saved.reason(), recorded) ? saved.reasonSource()
                    : leaf != null && Objects.equals(leaf.reason(), recorded) ? leaf.reasonSource()
                    : step != null && Objects.equals(step.reason(), recorded) ? step.reasonSource() : ReasonSource.AI_SCORING;
            return new Evidence(recorded, source);
        }
        if (saved != null) return new Evidence(saved.reason(), saved.reasonSource());
        if (leaf != null) return new Evidence(leaf.reason(), leaf.reasonSource());
        if (step != null) return new Evidence(step.reason(), step.reasonSource());
        return new Evidence(null, ReasonSource.MISSING);
    }
    public static String source(Evidence evidence, DecisionReportLabels labels) {
        if (evidence.source() == null) return labels.missingReason();
        return switch (evidence.source()) {
            case AI_SCORING -> labels.aiReason();
            case DETERMINISTIC_TRACE -> labels.deterministicReason();
            case MISSING -> labels.missingReason();
        };
    }
    public static String status(DecisionTreeRow row, DecisionReportLabels labels) {
        return switch (row.disposition()) {
            case CONTINUED -> labels.continued(); case LEAF_CANDIDATE -> labels.leafCandidate();
            case REJECTED -> labels.rejected(); case NOT_EVALUATED -> labels.notEvaluated();
        };
    }
    public boolean includesFullReasons() {
        var options = report.scope().options();
        // Compact controls the overview and optional appendices, never the completeness
        // of an original reason referenced by a displayed decision.
        return options.includes(Section.SUMMARY) || options.includes(Section.TREE)
                || options.includes(Section.CHAPTERS) || options.includes(Section.EVIDENCE);
    }
    public boolean hasOriginal(String code) {
        var node = nodes.get(code);
        return node != null && node.evidence().text() != null && !node.evidence().text().isBlank();
    }
    public Set<String> linkedReasonCodes() {
        if (!includesFullReasons()) return Set.of();
        return nodes.keySet().stream().filter(this::hasOriginal).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public void writeOverview(XWPFDocument document, DecisionReportLabels labels) {
        var writer = new WordDocumentWriter(document, labels);
        writer.heading(labels.german() ? "Ergebnisse je Taxonomie" : "Results by taxonomy", 2, null);
        writer.paragraph(labels.german()
                ? "Effektive Relevanz ist keine statistische Wahrscheinlichkeit. Lokaler Anteil und Produkteignung bleiben getrennte Bewertungsgrößen."
                : "Effective relevance is not a statistical probability. Local share and product suitability remain distinct measures.");
        var table = writer.table(List.of(labels.german() ? "Taxonomie" : "Taxonomy", labels.score(),
                labels.german() ? "Repräsentatives Ergebnis" : "Representative result",
                labels.german() ? "Gespeicherte Begründung / Navigation" : "Saved reason / navigation"), List.of());
        for (var group : roots.values()) {
            Node root = group.getFirst();
            var row = table.createRow(); row.setCantSplitRow(true);
            writer.cell(row.getCell(0), root.row().code() + " · " + root.row().title());
            String recordedState = root.row().score() == null || root.row().score() == 0d && root.row().disposition() == Disposition.REJECTED
                    ? status(root.row(), labels) : score(root.row(), labels) + " · " + status(root.row(), labels);
            writer.cell(row.getCell(1), recordedState);
            var candidates = group.stream().filter(n -> n.row().disposition() == Disposition.LEAF_CANDIDATE && n.row().score() != null)
                    .sorted(Comparator.comparingDouble((Node n) -> n.row().score()).reversed().thenComparing(n -> n.row().code())).toList();
            Node representative = candidates.isEmpty() ? root : candidates.getFirst();
            long ties = candidates.stream().filter(n -> Objects.equals(n.row().score(), representative.row().score())).count();
            writer.cell(row.getCell(2), String.join(" → ", representative.path())
                    + (representative == root ? "" : " · " + representative.row().title() + " · " + score(representative.row(), labels))
                    + (ties > 1 ? " · " + ties + (labels.german() ? " gleichauf; keine eindeutige Rangfolge" : " tied; no unique winner") : ""));
            var excerptNodes = new ArrayList<Node>();
            if (hasOriginal(root.row().code())) excerptNodes.add(root);
            if (representative != root && hasOriginal(representative.row().code())
                    && !representative.evidence().equals(root.evidence())) excerptNodes.add(representative);
            // Historical compact sources can carry essential ranked evidence even when no leaf was frozen in the tree.
            if (excerptNodes.isEmpty()) report.leadingLeaves().stream().filter(leaf -> root.root().equals(leaf.taxonomyRoot()))
                    .map(leaf -> nodes.get(leaf.code())).filter(Objects::nonNull).filter(node -> hasOriginal(node.row().code()))
                    .findFirst().ifPresent(excerptNodes::add);
            var reasonCell = row.getCell(3);
            if (excerptNodes.isEmpty()) writer.cell(reasonCell, labels.missingReason());
            for (var node : excerptNodes) {
                var p = excerptNodes.indexOf(node) == 0 ? reasonCell.getParagraphs().getFirst() : reasonCell.addParagraph();
                p.setSpacingAfter(60);
                var run = p.createRun(); run.setFontSize(10);
                run.setText(node.row().code() + " · " + source(node.evidence(), labels) + ": " + excerpt(node.evidence().text()));
                if (includesFullReasons()) writer.link(p, reasonBookmark(node.row().code()), " · Original", 10);
            }
            if (report.scope().options().includes(Section.TREE) && treeContains(root.row().code()))
                writer.link(reasonCell.addParagraph(), rootBookmark(root.row().code()), labels.treeOverview() + " · " + root.row().code(), 10);
        }
        writer.columnWidths(table, 18, 16, 28, 38);
        table.getRows().forEach(row -> row.getTableCells().forEach(cell -> cell.getParagraphs().forEach(p -> p.getRuns().forEach(r -> r.setFontSize(10)))));
    }
    private boolean treeContains(String code) {
        return report.scope().decisionTree().rows().stream().anyMatch(row -> row.code().equals(code));
    }
    private static String score(DecisionTreeRow row, DecisionReportLabels labels) {
        return row.score() == null ? labels.notEvaluated() : AnalysisPercentages.display(row.score());
    }
    private static String excerpt(String text) {
        int count = text.codePointCount(0, text.length());
        return count <= 120 ? text : text.substring(0, text.offsetByCodePoints(0, 120)) + "…";
    }
    public void writeReasons(XWPFDocument document, DecisionReportLabels labels) {
        if (linkedReasonCodes().isEmpty()) return;
        var writer = new WordDocumentWriter(document, labels);
        writer.heading(labels.german() ? "Gespeicherte Knotenbegründungen" : "Recorded node reasons", 1, "decision_node_reasons");
        for (var group : roots.values()) {
            if (group.stream().noneMatch(node -> hasOriginal(node.row().code()))) continue;
            writer.heading(group.getFirst().row().code() + " · " + group.getFirst().row().title(), 2, null);
            var shared = new LinkedHashMap<Evidence, List<Node>>();
            group.stream().filter(node -> hasOriginal(node.row().code()))
                    .forEach(node -> shared.computeIfAbsent(node.evidence(), ignored -> new ArrayList<>()).add(node));
            for (var entry : shared.entrySet()) {
                var p = writer.paragraph(String.join(", ", entry.getValue().stream().map(n -> n.row().code()).toList())
                        + " · " + source(entry.getKey(), labels));
                p.setKeepNext(true);
                for (var node : entry.getValue()) writer.bookmark(p, reasonBookmark(node.row().code()));
                String reason = entry.getKey().text();
                writer.paragraph(reason == null || reason.isBlank() ? labels.missingReason() : reason);
            }
            if (report.scope().options().includes(Section.TREE) && treeContains(group.getFirst().root()))
                writer.link(writer.paragraph(""), rootBookmark(group.getFirst().root()), labels.german() ? "Zurück zum Wurzelbaum" : "Back to root tree");
        }
    }
}
