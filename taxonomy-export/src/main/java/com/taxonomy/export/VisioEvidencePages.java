package com.taxonomy.export;

import com.taxonomy.visio.*;
import com.taxonomy.visio.converter.VisioPropertyLabels;

import java.awt.Font;
import java.util.*;

/** Readable, mandatory evidence pages supplement the compact native graph. */
final class VisioEvidencePages {
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    private static final int LINES_PER_PAGE = 54;
    private static final double WIDTH = 7.5;
    private static final double LINE_HEIGHT = .185;
    private static final List<String> SUMMARY_FIELDS = List.of("taxonomy.evidence1quote", "taxonomy.evidence1contribution",
            "taxonomy.evidence1condition", "taxonomy.evidence1rationale", "taxonomy.decisionRationale");
    private static final Map<String, String> REASON_FIELDS = reasonFields();

    private VisioEvidencePages() { }

    static void append(VisioDocument document, VisioPage overview) {
        boolean german = document.isGerman();
        var entries = new ArrayList<Entry>();
        for (var node : overview.getShapes()) {
            var values = node.getProperties();
            var reasons = reasons(values, german);
            String label = value(values, "taxonomy.label");
            String id = value(values, "taxonomy.id");
            String key = value(values, "taxonomy.displayKey");
            String identity = key + " | " + id;
            String unitName = value(values, "taxonomy.responsibilityName");
            boolean assigned = values.containsKey("taxonomy.responsibilityUnitId");
            boolean clipped = !VisioPresentation.display(label, 24, 3).replace("\n", "").equals(label.replace("\n", ""))
                    || !VisioPresentation.display(identity, 24, 1).replace("\n", "").equals(identity.replace("\n", ""))
                    || (assigned && !VisioPresentation.nodeFooter("", unitName, 24, german)
                        .equals(VisioPresentation.unitLabel(unitName, german)));
            if (reasons.isEmpty() && !clipped) continue;
            var fields = new ArrayList<String>();
            fields.add(key + " · " + id + " · " + label);
            if (assigned) {
                fields.add((german ? "Zuständige Einheit: " : "Responsible unit: ") + unitName + " ("
                        + value(values, "taxonomy.responsibilityUnitId") + ")");
                fields.add((german ? "Quelle der Zuständigkeit: " : "Assignment source: ")
                        + value(values, "taxonomy.responsibilitySourceId"));
            }
            fields.addAll(reasons);
            entries.add(new Entry(key, fields, values));
        }
        for (var relation : overview.getConnects()) {
            var values = relation.getProperties();
            var reasons = reasons(values, german);
            boolean clipped = !VisioPresentation.display(relation.getRelationType(), 52, 4).replace("\n", "")
                    .equals(relation.getRelationType().replace("\n", ""));
            if (reasons.isEmpty() && !clipped) continue;
            String key = value(values, "taxonomy.displayKey");
            var fields = new ArrayList<String>();
            fields.add(key + " · " + value(values, "taxonomy.id") + " · "
                    + value(values, "taxonomy.sourceId") + " → " + value(values, "taxonomy.targetId"));
            fields.add(german ? "Typ: " + VisioPresentation.relationLabel(relation.getRelationType(), true)
                    : "Type: " + relation.getRelationType());
            if (values.containsKey("taxonomy.provenanceStatus")) fields.add("Status: "
                    + VisioPresentation.statusLabel(value(values, "taxonomy.provenanceStatus"), german));
            fields.addAll(reasons);
            entries.add(new Entry(key, fields, values));
        }
        if (entries.isEmpty()) return;
        appendTextPages(document, entries, german ? "Nachweise " : "Evidence ",
                german ? "Vollständige Nachweise · " : "Full recorded evidence · ",
                german ? " · N = Knoten, R = Beziehung" : " · N = node, R = relationship");

        var links = new HashMap<String, VisioProperty>();
        entries.forEach(entry -> links.put(entry.key(), entry.properties().get("taxonomy.evidencePage")));
        // Impact pages contain independent copies of native graph shapes; their evidence links agree.
        for (var page : document.getPages()) {
            page.getShapes().forEach(shape -> copyLink(shape.getProperties(), links));
            page.getConnects().forEach(relation -> copyLink(relation.getProperties(), links));
        }
        String notice = german ? "Vollständige Begründungen und Bezeichnungen auf den Nachweisseiten (Kürzel N/R)."
                : "Full recorded reasons and labels: Evidence pages, matched by N/R keys.";
        var legend = overview.getLegend();
        double top = overview.getShapes().stream().mapToDouble(shape -> shape.getY() + shape.getHeight() / 2).max().orElse(0);
        top = Math.max(top, overview.getConnects().stream()
                .filter(connect -> connect.getTextBox() != null && !connect.getTextBox().text().isBlank())
                .mapToDouble(connect -> connect.getTextBox().y() + connect.getTextBox().height() / 2).max().orElse(0));
        if (legend == null) {
            overview.setLegend(new VisioTextBox(notice, 5.5, top + .7, 10, .6));
        } else {
            double y = Math.max(legend.y(), top + .4 + legend.height() / 2);
            overview.setLegend(new VisioTextBox(legend.text() + "\n" + notice, legend.x(), y, legend.width(), legend.height()));
        }
        document.getLosses().add(new VisioLoss("document", "", "visibleEvidence", "MAPPED",
                "Full recorded reasons and long labels are readable on Evidence pages, identified by the overview N/R keys. Text continues onto following pages without shortening."));
    }

    static void appendTreeReasons(VisioDocument document, int firstDecisionPage, boolean german) {
        var entries = new ArrayList<Entry>();
        for (var page : document.getPages().subList(firstDecisionPage, document.getPages().size())) {
            for (var shape : page.getShapes()) {
                var values = shape.getProperties();
                var reason = values.get("taxonomy.presenceReason");
                if (reason == null || reason.value().isBlank()) continue;
                String id = value(values, "taxonomy.id");
                var fields = List.of(id + " · " + value(values, "taxonomy.label"), reason.value());
                entries.add(new Entry(id, fields, values));
            }
        }
        if (!entries.isEmpty()) {
            appendTextPages(document, entries, german ? "Entscheidungsgründe " : "Decision reasons ", "", "");
        }
    }

    /** Show the most useful recorded values beside a single native relationship; link to their complete record. */
    static VisioShape relationExcerpt(String shapeId, VisioConnect relation, boolean german) {
        var values = relation.getProperties();
        var evidencePage = values.get("taxonomy.evidencePage");
        if (evidencePage == null) return null;

        double width = 10;
        var lines = new ArrayList<String>();
        lines.add(german ? "Beziehungsbeleg · " + value(values, "taxonomy.displayKey")
                : "Relationship evidence · " + value(values, "taxonomy.displayKey"));
        var shown = new LinkedHashSet<String>();
        for (String field : SUMMARY_FIELDS) {
            String recorded = value(values, field);
            if (recorded.isBlank() || !shown.add(recorded)) continue;
            var wrapped = DiagramTextWrapper.wrapMeasured(VisioPropertyLabels.label(field, german) + ": " + recorded.strip(),
                    FONT, (width * 72 - 20) / 1.15, 6, "…");
            lines.add("");
            lines.addAll(wrapped);
        }
        if (shown.isEmpty()) return null;
        lines.add("");
        lines.add((german ? "Vollständige Nachweise: " : "Complete evidence: ") + evidencePage.value());
        double height = .45 + lines.size() * LINE_HEIGHT;
        var shape = new VisioShape(shapeId, String.join("\n", lines), 5.5, .5 + height / 2,
                width, height, "Evidence", false);
        shape.getProperties().put("taxonomy.presentationOnly", VisioProperty.bool(true));
        shape.getProperties().put("taxonomy.evidencePage", evidencePage);
        return shape;
    }

    private static void appendTextPages(VisioDocument document, List<Entry> entries, String prefix,
                                        String titlePrefix, String titleSuffix) {

        var pages = new ArrayList<List<String>>();
        var current = new ArrayList<String>();
        for (var entry : entries) {
            var lines = new ArrayList<String>();
            for (String field : entry.fields()) {
                // A conservative allowance protects the readable 10pt size if Visio substitutes fonts.
                lines.addAll(DiagramTextWrapper.wrapMeasured(field, FONT, (WIDTH * 72 - 20) / 1.15,
                        Integer.MAX_VALUE, ""));
            }
            if (!current.isEmpty() && LINES_PER_PAGE - current.size() < Math.min(3, lines.size())) {
                pages.add(List.copyOf(current)); current.clear();
            }
            entry.properties().put("taxonomy.evidencePage", VisioProperty.text(prefix + (pages.size() + 1)));
            for (String line : lines) {
                if (current.size() == LINES_PER_PAGE) { pages.add(List.copyOf(current)); current.clear(); }
                current.add(line);
            }
            if (current.size() < LINES_PER_PAGE) current.add("");
        }
        if (!current.isEmpty()) pages.add(List.copyOf(current));
        long existingEvidence = document.getPages().stream().filter(VisioPage::isEvidencePage).count();
        if (existingEvidence + pages.size() > VisioPackageValidator.MAX_EVIDENCE_PAGES
                || document.getPages().size() + pages.size() > VisioPackageValidator.MAX_PAGES) {
            throw new IllegalArgumentException("Complete readable Visio evidence exceeds the "
                    + VisioPackageValidator.MAX_EVIDENCE_PAGES
                    + "-page evidence capacity; select fewer elements. No reasons were truncated.");
        }
        int count = 0;
        for (var lines : pages) {
            String name = prefix + (++count);
            var page = new VisioPage(Integer.toString(document.getPages().size()), name);
            page.setEvidencePage(true);
            double height = .2 + lines.size() * LINE_HEIGHT;
            var text = new VisioShape("1", String.join("\n", lines), 4.15, 10.65 - height / 2,
                    WIDTH, height, "Evidence", false);
            text.getProperties().put("taxonomy.presentationOnly", VisioProperty.bool(true));
            page.getShapes().add(text);
            page.setLegend(new VisioTextBox(titlePrefix + name + " / " + pages.size() + titleSuffix, 4.15, 11.05, WIDTH, .4));
            document.getPages().add(page);
        }
        int existingEvidencePages = Integer.parseInt(document.getProperties()
                .getOrDefault("taxonomy.evidencePages", VisioProperty.number(0)).value());
        document.getProperties().put("taxonomy.evidencePages", VisioProperty.number(existingEvidencePages + pages.size()));
    }

    private static void copyLink(Map<String, VisioProperty> values, Map<String, VisioProperty> links) {
        var link = links.get(value(values, "taxonomy.displayKey"));
        if (link != null) values.put("taxonomy.evidencePage", link);
    }

    private static List<String> reasons(Map<String, VisioProperty> values, boolean german) {
        var grouped = new LinkedHashMap<String, List<String>>();
        for (var entry : values.entrySet()) {
            String label = REASON_FIELDS.get(entry.getKey());
            if (label == null && entry.getKey().matches("taxonomy\\.evidence[0-9]+.+")) {
                label = entry.getKey().substring("taxonomy.".length());
            }
            if (label != null && entry.getValue() != null && !entry.getValue().value().isBlank()) {
                if (german) label = VisioPropertyLabels.label(entry.getKey(), true);
                grouped.computeIfAbsent(entry.getValue().value(), ignored -> new ArrayList<>()).add(label);
            }
        }
        return grouped.entrySet().stream().map(entry -> String.join(" / ", entry.getValue()) + ": " + entry.getKey()).toList();
    }

    private static String value(Map<String, VisioProperty> values, String key) {
        var value = values.get(key);
        return value == null ? "" : value.value();
    }

    private static Map<String, String> reasonFields() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("taxonomy.presenceReason", "Presence reason");
        fields.put("taxonomy.selectionReason", "Selection reason");
        fields.put("taxonomy.includedBecause", "Included because");
        fields.put("taxonomy.derivationReason", "Derivation reason");
        fields.put("taxonomy.decisionRationale", "Review rationale");
        fields.put("taxonomy.actionEvidence", "Action evidence");
        fields.put("taxonomy.reason", "Reason");
        fields.put("taxonomy.rationale", "Rationale");
        return Collections.unmodifiableMap(fields);
    }

    private record Entry(String key, List<String> fields, Map<String, VisioProperty> properties) { }
}
