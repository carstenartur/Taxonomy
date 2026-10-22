package com.taxonomy.export;

import com.taxonomy.visio.*;

import java.awt.Font;
import java.util.*;

/** Readable, mandatory evidence pages supplement the compact native graph. */
final class VisioEvidencePages {
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    private static final int LINES_PER_PAGE = 54;
    private static final double WIDTH = 7.5;
    private static final double LINE_HEIGHT = .185;
    private static final Map<String, String> REASON_FIELDS = reasonFields();

    private VisioEvidencePages() { }

    static void append(VisioDocument document, VisioPage overview) {
        var entries = new ArrayList<Entry>();
        for (var node : overview.getShapes()) {
            var values = node.getProperties();
            var reasons = reasons(values);
            String label = value(values, "taxonomy.label");
            String id = value(values, "taxonomy.id");
            String key = value(values, "taxonomy.displayKey");
            String identity = key + " | " + id;
            boolean clipped = !VisioPresentation.display(label, 24, 3).replace("\n", "").equals(label.replace("\n", ""))
                    || !VisioPresentation.display(identity, 24, 1).replace("\n", "").equals(identity.replace("\n", ""));
            if (reasons.isEmpty() && !clipped) continue;
            var fields = new ArrayList<String>();
            fields.add(key + " · " + id + " · " + label);
            fields.addAll(reasons);
            entries.add(new Entry(key, fields, values));
        }
        for (var relation : overview.getConnects()) {
            var values = relation.getProperties();
            var reasons = reasons(values);
            boolean clipped = !VisioPresentation.display(relation.getRelationType(), 52, 4).replace("\n", "")
                    .equals(relation.getRelationType().replace("\n", ""));
            if (reasons.isEmpty() && !clipped) continue;
            String key = value(values, "taxonomy.displayKey");
            var fields = new ArrayList<String>();
            fields.add(key + " · " + value(values, "taxonomy.id") + " · "
                    + value(values, "taxonomy.sourceId") + " → " + value(values, "taxonomy.targetId"));
            fields.add("Type: " + relation.getRelationType());
            if (values.containsKey("taxonomy.provenanceStatus")) fields.add("Status: " + value(values, "taxonomy.provenanceStatus"));
            fields.addAll(reasons);
            entries.add(new Entry(key, fields, values));
        }
        if (entries.isEmpty()) return;

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
            entry.properties().put("taxonomy.evidencePage", VisioProperty.text("Evidence " + (pages.size() + 1)));
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
        var links = new HashMap<String, VisioProperty>();
        entries.forEach(entry -> links.put(entry.key(), entry.properties().get("taxonomy.evidencePage")));
        // Impact pages contain independent copies of native graph shapes; their evidence links agree.
        for (var page : document.getPages()) {
            page.getShapes().forEach(shape -> copyLink(shape.getProperties(), links));
            page.getConnects().forEach(relation -> copyLink(relation.getProperties(), links));
        }
        int count = 0;
        for (var lines : pages) {
            String name = "Evidence " + (++count);
            var page = new VisioPage(Integer.toString(document.getPages().size()), name);
            page.setEvidencePage(true);
            double height = .2 + lines.size() * LINE_HEIGHT;
            var text = new VisioShape("1", String.join("\n", lines), 4.15, 10.65 - height / 2,
                    WIDTH, height, "Evidence", false);
            text.getProperties().put("taxonomy.presentationOnly", VisioProperty.bool(true));
            page.getShapes().add(text);
            page.setLegend(new VisioTextBox("Full recorded evidence · " + name + " / " + pages.size()
                    + " · N = node, R = relationship", 4.15, 11.05, WIDTH, .4));
            document.getPages().add(page);
        }
        document.getProperties().put("taxonomy.evidencePages", VisioProperty.number(pages.size()));
        String notice = "Full recorded reasons and labels: Evidence pages, matched by N/R keys.";
        var legend = overview.getLegend();
        if (legend == null) {
            double top = overview.getShapes().stream().mapToDouble(shape -> shape.getY() + shape.getHeight() / 2).max().orElse(0);
            overview.setLegend(new VisioTextBox(notice, 5.5, top + .7, 10, .6));
        } else {
            overview.setLegend(new VisioTextBox(legend.text() + "\n" + notice, legend.x(), legend.y(), legend.width(), legend.height()));
        }
        document.getLosses().add(new VisioLoss("document", "", "visibleEvidence", "MAPPED",
                "Full recorded reasons and long labels are readable on Evidence pages, identified by the overview N/R keys. Text continues onto following pages without shortening."));
    }

    private static void copyLink(Map<String, VisioProperty> values, Map<String, VisioProperty> links) {
        var link = links.get(value(values, "taxonomy.displayKey"));
        if (link != null) values.put("taxonomy.evidencePage", link);
    }

    private static List<String> reasons(Map<String, VisioProperty> values) {
        var grouped = new LinkedHashMap<String, List<String>>();
        for (var entry : values.entrySet()) {
            String label = REASON_FIELDS.get(entry.getKey());
            if (label == null && entry.getKey().matches("taxonomy\\.evidence[0-9]+.+")) {
                label = entry.getKey().substring("taxonomy.".length());
            }
            if (label != null && entry.getValue() != null && !entry.getValue().value().isBlank())
                grouped.computeIfAbsent(entry.getValue().value(), ignored -> new ArrayList<>()).add(label);
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
