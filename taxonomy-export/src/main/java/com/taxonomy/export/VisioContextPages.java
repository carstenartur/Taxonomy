package com.taxonomy.export;

import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioLoss;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioProperty;
import com.taxonomy.visio.VisioShape;
import com.taxonomy.visio.VisioTextBox;

import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Editable source context, with complete requirement text on mandatory pages. */
final class VisioContextPages {
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    private static final double PAGE_WIDTH = 7.5;
    private static final double LINE_HEIGHT = .20;
    private static final double TOP = 10.65;
    private static final String UNKNOWN = "Not recorded";
    private static final List<String> SHAPE_CONTEXT = List.of("projectId", "projectKey", "requirementId",
            "requirementKey", "requirementTextProvenance", "requirementVersionId", "requirementVersionCreatedBy", "requirementVersionCreatedAt",
            "snapshotId", "snapshotCreatedAt", "workspaceId", "branch", "generatorName", "generatorVersion",
            "exportedBy", "exportedAt", "requirementUrl", "requirementUrlStatus");

    private VisioContextPages() { }

    /** Called before optional relationship pages so complete source text reserves its capacity first. */
    static void append(VisioDocument document) {
        if (!hasContext(document)) return;
        // Reject oversized source and unsafe metadata before measured text wrapping.
        VisioPackageValidator.validateProperties(document.getProperties());
        if (document.getProperties().containsKey("taxonomy.requirementContextPages")) return;
        String source = value(document, "requirementText");
        if (source != null && !source.isBlank()) {
            var heading = wrap(contextLines(document), PAGE_WIDTH);
            double headingHeight = height(heading);
            int linesPerPage = (int) Math.floor((TOP - headingHeight - .4 - .5) / LINE_HEIGHT);
            if (linesPerPage < 4) throw capacity();
            var lines = wrap(List.of(source), PAGE_WIDTH);
            int count = (lines.size() + linesPerPage - 1) / linesPerPage;
            long existing = document.getPages().stream().filter(VisioPage::isEvidencePage).count();
            if (existing + count > VisioPackageValidator.MAX_EVIDENCE_PAGES
                    || document.getPages().size() + count > VisioPackageValidator.MAX_PAGES) throw capacity();
            for (int index = 0; index < count; index++) {
                var page = new VisioPage(Integer.toString(document.getPages().size()),
                        requirementPageName(document, index + 1));
                if (index == 0) document.getProperties().put("taxonomy.requirementStartPage", VisioProperty.text(page.getName()));
                page.setEvidencePage(true);
                var header = text("1", heading, .4, TOP, PAGE_WIDTH);
                copyContext(document, header);
                page.getShapes().add(header);
                int start = index * linesPerPage;
                var body = text("2", lines.subList(start, Math.min(lines.size(), start + linesPerPage)),
                        .4, TOP - headingHeight - .4, PAGE_WIDTH);
                body.getProperties().put("taxonomy.requirementContent", VisioProperty.bool(true));
                page.getShapes().add(body);
                page.setLegend(new VisioTextBox("Original requirement · " + (index + 1) + " / " + count
                        + " · complete source text", 4.15, 11.05, PAGE_WIDTH, .4));
                document.getPages().add(page);
            }
            document.getProperties().put("taxonomy.requirementContextPages", VisioProperty.number(count));
            document.getLosses().add(new VisioLoss("document", "", "visibleRequirement", "MAPPED",
                    "The diagram context contains an explicitly bounded excerpt; complete original requirement text "
                            + "is retained on Requirement pages and in taxonomy.requirementText."));
        } else {
            document.getProperties().put("taxonomy.requirementContextPages", VisioProperty.number(0));
        }
        decorateDiagrams(document);
    }

    /** Also called after optional details, which must receive the same creation/source context. */
    static void decorateDiagrams(VisioDocument document) {
        if (!hasContext(document)) return;
        for (var page : document.getPages()) {
            if (page.isEvidencePage() || page.getShapes().stream()
                    .anyMatch(shape -> shape.getProperties().containsKey("taxonomy.contextBlock"))) continue;
            var fields = contextLines(document);
            String source = value(document, "requirementText");
            if (source != null && !source.isBlank()) {
                var original = wrap(List.of(source), 10);
                fields.add("Original requirement" + (original.size() > 4 ? " (excerpt):" : ":"));
                fields.addAll(original.subList(0, Math.min(4, original.size())));
                fields.add("Complete original text: " + value(document, "requirementStartPage")
                        + (document.getProperties().containsKey("taxonomy.requirementContextPages")
                        && Integer.parseInt(value(document, "requirementContextPages")) > 1 ? " and following pages." : "."));
            } else {
                fields.add("Original requirement: " + UNKNOWN + ".");
            }
            double top = page.getShapes().stream().mapToDouble(s -> s.getY() + s.getHeight() / 2).max().orElse(.5);
            top = Math.max(top, page.getConnects().stream().filter(c -> c.getTextBox() != null)
                    .mapToDouble(c -> c.getTextBox().y() + c.getTextBox().height() / 2).max().orElse(.5));
            if (page.getLegend() != null) top = Math.max(top, page.getLegend().y() + page.getLegend().height() / 2);
            long nextId = page.getShapes().stream().mapToLong(s -> Long.parseLong(s.getId())).max().orElse(0) + 1;
            var wrapped = wrap(fields, 10);
            var header = text(Long.toString(nextId), wrapped, .5, top + .35 + height(wrapped), 10);
            copyContext(document, header);
            header.getProperties().put("taxonomy.contextBlock", VisioProperty.bool(true));
            if (source != null && !source.isBlank()) {
                header.getProperties().put("taxonomy.evidencePage", VisioProperty.text(value(document, "requirementStartPage")));
            }
            page.getShapes().add(header);
        }
    }

    private static ArrayList<String> contextLines(VisioDocument document) {
        var lines = new ArrayList<String>();
        lines.add("Requirement: " + label(document, "requirementKey") + " · " + label(document, "requirementTitle"));
        lines.add("Project: " + label(document, "projectKey") + " | Requirement version: " + label(document, "requirementVersionId"));
        lines.add("Version author: " + label(document, "requirementVersionCreatedBy")
                + " | Version created: " + label(document, "requirementVersionCreatedAt"));
        lines.add("Analysis created: " + label(document, "snapshotCreatedAt") + " | Snapshot: " + label(document, "snapshotId"));
        lines.add("Exported by: " + label(document, "exportedBy") + " | Export created: " + label(document, "exportedAt"));
        lines.add("Created with: " + label(document, "generatorName") + " " + label(document, "generatorVersion"));
        lines.add("Workspace: " + label(document, "workspaceId") + " | Branch: " + label(document, "branch"));
        if (value(document, "requirementTextProvenance") != null)
            lines.add(label(document, "requirementTextProvenance"));
        String link = value(document, "requirementUrl");
        if (link != null && !link.isBlank()) {
            lines.add("Edit in Taxonomy: " + label(document, "requirementUrl"));
            lines.add("Sign in and select the source workspace/branch to edit. The link grants no access.");
        } else {
            lines.add("Edit in Taxonomy: " + (value(document, "requirementUrlStatus") == null
                    ? "No saved requirement link is available." : label(document, "requirementUrlStatus")));
        }
        return lines;
    }

    private static VisioShape text(String id, List<String> lines, double left, double top, double width) {
        double height = height(lines);
        var shape = new VisioShape(id, String.join("\n", lines), left + width / 2, top - height / 2,
                width, height, "Evidence", false);
        shape.getProperties().put("taxonomy.presentationOnly", VisioProperty.bool(true));
        return shape;
    }

    private static double height(List<String> lines) { return .25 + lines.size() * LINE_HEIGHT; }

    private static String requirementPageName(VisioDocument document, int index) {
        String base = "Requirement " + index;
        String candidate = base;
        int suffix = 1;
        while (nameExists(document, candidate)) candidate = base + " (source " + suffix++ + ")";
        return candidate;
    }

    private static boolean nameExists(VisioDocument document, String name) {
        return document.getPages().stream().anyMatch(page -> page.getName().equalsIgnoreCase(name));
    }

    private static void copyContext(VisioDocument document, VisioShape shape) {
        for (String key : SHAPE_CONTEXT) {
            var property = document.getProperties().get("taxonomy." + key);
            if (property != null) shape.getProperties().put("taxonomy." + key, property);
        }
    }

    private static List<String> wrap(List<String> fields, double width) {
        var lines = new ArrayList<String>();
        for (String field : fields) {
            for (String paragraph : field.split("\\R", -1)) {
                if (paragraph.isBlank()) lines.add("");
                else lines.addAll(DiagramTextWrapper.wrapMeasured(paragraph, FONT, (width * 72 - 20) / 1.15,
                        Integer.MAX_VALUE, ""));
            }
        }
        return lines;
    }

    private static String label(VisioDocument document, String key) {
        String value = value(document, key);
        if (value == null || value.isBlank()) return UNKNOWN;
        String displayed = VisioPresentation.display(value, 120, 1);
        if (!displayed.equals(value)) {
            var loss = new VisioLoss("document", "", key, "TRUNCATED",
                    "Visible context label shortened; full value remains in native document/shape data.");
            if (!document.getLosses().contains(loss)) document.getLosses().add(loss);
        }
        return displayed;
    }

    private static String value(VisioDocument document, String key) {
        var property = document.getProperties().get("taxonomy." + key);
        return property == null ? null : property.value();
    }

    static boolean hasContext(VisioDocument document) {
        Map<String, VisioProperty> values = document.getProperties();
        return values.containsKey("taxonomy.requirementText") || values.containsKey("taxonomy.generatorName")
                || values.containsKey("taxonomy.exportedBy");
    }

    private static IllegalArgumentException capacity() {
        return new IllegalArgumentException("Complete readable Visio requirement exceeds page capacity; "
                + "no original requirement text was truncated.");
    }
}
