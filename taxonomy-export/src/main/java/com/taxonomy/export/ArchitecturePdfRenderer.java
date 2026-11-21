package com.taxonomy.export;

import com.taxonomy.diagram.DiagramCanonicalEvidence;
import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.diagram.DiagramSceneEdge;
import com.taxonomy.diagram.DiagramSceneNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Draws a vector PDF directly from the same deterministic scene returned to other export adapters. */
public final class ArchitecturePdfRenderer {

    private final ExportThemeProvider themes;

    public ArchitecturePdfRenderer() { this(ExportThemeProvider.defaults()); }

    public ArchitecturePdfRenderer(ExportThemeProvider themes) {
        this.themes = java.util.Objects.requireNonNull(themes, "themes");
    }

    private static final float PAGE_MARGIN = PdfDiagramPages.PAGE_MARGIN;
    private static final float HEADER_HEIGHT = PdfDiagramPages.HEADER_HEIGHT;

    private record Fonts(PDFont regular, PDFont bold) { }

    private record Decoration(String title, List<String> header, List<String> footer,
                              List<String> warnings, DiagramCanonicalEvidence canonical) { }

    /** Neutral metadata required to decorate a persisted architecture snapshot export. */
    public record SnapshotMetadata(
            String projectKey,
            String requirementKey,
            String snapshotId,
            String provider,
            String branchName,
            String commitSha,
            List<String> warnings,
            String analysisStatus) {

        public SnapshotMetadata(String projectKey, String requirementKey, String snapshotId,
                                String provider, String branchName, String commitSha, List<String> warnings) {
            this(projectKey, requirementKey, snapshotId, provider, branchName, commitSha, warnings, null);
        }

        public SnapshotMetadata {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public byte[] render(DiagramScene scene, SnapshotMetadata metadata) {
        return render(scene, metadata, Map.of());
    }

    public byte[] render(DiagramScene scene, SnapshotMetadata metadata, Map<String, String> nodeReasons) {
        return render(scene, metadata, nodeReasons, Map.of());
    }

    public byte[] render(DiagramScene scene, SnapshotMetadata metadata, Map<String, String> nodeReasons,
                         Map<String, Map<String, String>> relationReasons) {
        return render(scene, metadata, nodeReasons, relationReasons, Locale.ENGLISH);
    }

    public byte[] render(DiagramScene scene, SnapshotMetadata metadata, Map<String, String> nodeReasons,
                         Map<String, Map<String, String>> relationReasons, Locale locale) {
        requireScene(scene);
        var language = DiagramLanguage.from(locale);
        if (metadata == null) throw new IllegalArgumentException("Architecture snapshot metadata is required");
        var header = new ArrayList<String>();
        header.add(language.text("Project ", "Projekt ") + metadata.projectKey()
                + language.text(" · Requirement ", " · Anforderung ") + metadata.requirementKey()
                + " · Snapshot " + metadata.snapshotId());
        header.add(language.text("Provider ", "Anbieter ") + safe(metadata.provider())
                + " · Branch " + safe(metadata.branchName()) + " · Commit " + abbreviated(metadata.commitSha()));
        if (metadata.analysisStatus() != null)
            header.add(language.text("Recorded analysis status: ", "Gespeicherter Analysestatus: ") + metadata.analysisStatus());
        var decoration = new Decoration(scene.title(), header, List.of(language.text(
                "Generated from persisted architecture snapshot; no page screenshot and no LLM re-analysis.",
                "Aus dem gespeicherten Architektursnapshot erzeugt. Keine Bildschirmaufnahme und keine erneute KI-Analyse.")),
                metadata.warnings(), null);
        return renderScene(scene, document -> configureMetadata(document, scene, metadata, language),
                decoration, nodeReasons, relationReasons, language);
    }

    /** Renders a supplied scene with external source identification. */
    public byte[] render(DiagramScene scene, String provenance) {
        requireScene(scene);
        return renderScene(scene, document -> configureSource(document, scene, provenance),
                new Decoration(scene.title(), safe(provenance).lines().limit(3).toList(),
                        List.of("Derived server layout · Git-authoritative architecture"), List.of(), null),
                Map.of(), Map.of(), DiagramLanguage.ENGLISH);
    }

    /** Canonical editor exports carry checkpoint fields independently of snapshot scores. */
    public byte[] renderCanonical(DiagramScene scene, String provenance, DiagramCanonicalEvidence evidence) {
        requireScene(scene);
        Objects.requireNonNull(evidence, "canonical evidence");
        var header = new ArrayList<>(safe(provenance).lines().limit(2).toList());
        String scope = evidence.viewId() == null || evidence.viewId().isBlank()
                ? "Scope: canonical workspace" : "Scope: canonical view " + evidence.viewId();
        var partial = evidence.analysisSources().stream().filter(source -> "PARTIAL".equals(source.status())).count();
        if (partial != 0) scope += " · PARTIAL analysis sources: " + partial;
        header.add(scope);
        return renderScene(scene, document -> configureSource(document, scene, provenance),
                new Decoration(scene.title(), header, List.of("Derived server layout · Canonical architecture"),
                        List.of(), evidence), Map.of(), Map.of(), DiagramLanguage.ENGLISH);
    }

    /** Supplied working data has no verified Git or persisted-snapshot authority. */
    public byte[] renderWorkingView(DiagramScene scene) {
        return renderWorkingView(scene, Map.of());
    }

    public byte[] renderWorkingView(DiagramScene scene, Map<String, String> nodeReasons) {
        return renderWorkingView(scene, nodeReasons, Map.of());
    }

    public byte[] renderWorkingView(DiagramScene scene, Map<String, String> nodeReasons,
                                    Map<String, Map<String, String>> relationReasons) {
        requireScene(scene);
        return renderScene(scene, document -> configureSource(document, scene, "Client-provided working view"),
                new Decoration("Requirement architecture - working view", List.of(
                        "Client-provided working view; source authority has not been verified.", scene.title()),
                        List.of("Generated server layout | Client-provided working view | No re-analysis or persistence"),
                        List.of(), null), nodeReasons, relationReasons, DiagramLanguage.ENGLISH);
    }

    private static void requireScene(DiagramScene scene) {
        if (scene == null || scene.isEmpty()) throw new IllegalArgumentException("Architecture scene must not be empty");
    }

    private static void configureSource(PDDocument document, DiagramScene scene, String provenance) {
        var information = new PDDocumentInformation();
        information.setTitle(scene.title());
        information.setSubject(provenance);
        information.setCreator("Taxonomy server-side vector renderer");
        document.setDocumentInformation(information);
    }

    private byte[] renderScene(DiagramScene scene, Consumer<PDDocument> metadata, Decoration decoration,
                               Map<String, String> nodeReasons, Map<String, Map<String, String>> relationReasons,
                               DiagramLanguage language) {
        ExportTheme theme = Objects.requireNonNull(themes.current(), "export theme");
        PdfDiagramPages.Plan plan = PdfDiagramPages.plan(scene);
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var fonts = new Fonts(PdfDiagramText.loadFont(document, false), PdfDiagramText.loadFont(document, true));
            metadata.accept(document);
            document.getDocumentCatalog().setLanguage(language.tag());
            float width = PdfDiagramPages.PAGE_WIDTH;
            float height = PdfDiagramPages.PAGE_HEIGHT;
            for (int index = 0; index < plan.panels().size(); index++) {
                var panel = plan.panels().get(index);
                DiagramScene part = panel.scene();
                PDPage page = new PDPage(new PDRectangle(width, height));
                document.addPage(page);
                float scale = (float) Math.min(1.0, Math.min(
                        (width - 2 * PAGE_MARGIN) / Math.max(1.0, part.width()),
                        (height - 2 * PAGE_MARGIN - HEADER_HEIGHT) / Math.max(1.0, part.height())));
                if (scale < 0.8f) throw new IllegalArgumentException("PDF panel is not readable");
                float diagramTop = height - PAGE_MARGIN - HEADER_HEIGHT;
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    background(stream, theme, width, height);
                    drawHeader(stream, decoration, width, height, theme, fonts);
                    drawEdges(stream, part, PAGE_MARGIN, diagramTop, scale, theme, language, fonts, decoration.canonical());
                    drawNodes(stream, part, PAGE_MARGIN, diagramTop, scale, theme, language, fonts, decoration.canonical() != null);
                    drawFooter(stream, decoration, width, theme, language, fonts.regular());
                    if (plan.panels().size() > 1) {
                        text(stream, theme, fonts.regular(), 8, PAGE_MARGIN, 30,
                                language.text("Diagram D", "Diagramm D") + (index + 1) + "/" + plan.panels().size()
                                        + language.text(" | Cross-page relationships: ", " | Seitenübergreifende Beziehungen: ") + panel.crossPageRelationships()
                                        + language.text(" | Complete endpoints and diagram references: see export inventory.", " | Endpunkte und Diagrammverweise siehe Exportinventar."));
                    }
                }
            }
            drawInventory(document, scene, plan.nodePages(), nodeReasons == null ? Map.of() : nodeReasons,
                    relationReasons == null ? Map.of() : relationReasons, theme, language, fonts, decoration);
            document.save(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to render architecture PDF", error);
        }
    }

    private static void background(PDPageContentStream stream, ExportTheme theme, float width, float height)
            throws IOException {
        stream.setNonStrokingColor(Color.decode(theme.canvasBackground()));
        stream.addRect(0, 0, width, height);
        stream.fill();
    }

    private static void configureMetadata(PDDocument document, DiagramScene scene, SnapshotMetadata metadata, DiagramLanguage language) {
        PDDocumentInformation information = new PDDocumentInformation();
        information.setTitle(scene.title());
        information.setSubject(language.text("Requirement-derived architecture snapshot ", "Anforderungsbezogener Architektursnapshot ") + metadata.snapshotId());
        information.setAuthor("Taxonomy Architecture Analyzer");
        information.setCreator("Taxonomy server-side vector renderer");
        information.setKeywords("architecture, taxonomy, requirement, snapshot, " + metadata.snapshotId());
        document.setDocumentInformation(information);
    }

    private static void drawHeader(PDPageContentStream stream, Decoration decoration, float pageWidth,
                                   float pageHeight, ExportTheme theme, Fonts fonts) throws IOException {
        float y = pageHeight - PAGE_MARGIN - 16;
        float width = pageWidth - 2 * PAGE_MARGIN;
        text(stream, theme, fonts.bold(), 16, PAGE_MARGIN, y,
                PdfDiagramText.wrap(decoration.title(), fonts.bold(), 16, width, 1).getFirst());
        for (int i = 0; i < decoration.header().size(); i++)
            text(stream, theme, fonts.regular(), 9, PAGE_MARGIN, y - 17 - i * 12,
                    PdfDiagramText.wrap(decoration.header().get(i), fonts.regular(), 9, width, 1).getFirst());
    }

    private record EdgeCaption(Rectangle2D.Float bounds, String text) { }

    private static void drawEdges(PDPageContentStream stream, DiagramScene scene, float diagramX,
                                  float diagramTop, float scale, ExportTheme theme, DiagramLanguage language,
                                  Fonts fonts, DiagramCanonicalEvidence canonical) throws IOException {
        var nodeBounds = scene.nodes().stream().map(node -> new Rectangle2D.Float(
                diagramX + (float) node.x() * scale,
                diagramTop - (float) (node.y() + node.height()) * scale,
                (float) node.width() * scale, (float) node.height() * scale)).toList();
        var captions = new ArrayList<EdgeCaption>();
        float labelSize = 8;
        for (DiagramSceneEdge edge : scene.edges()) {
            float x1 = diagramX + (float) edge.sourceX() * scale;
            float y1 = diagramTop - (float) edge.sourceY() * scale;
            float x2 = diagramX + (float) edge.targetX() * scale;
            float y2 = diagramTop - (float) edge.targetY() * scale;
            boolean proposed = canonical == null ? edge.metadata() != null && edge.metadata().proposed()
                    : DiagramPresentation.canonicalRelationshipProposed(canonical, edge.id());
            stream.setStrokingColor(Color.decode(theme.relationshipStroke(edge.relationType(), proposed)));
            stream.setLineDashPattern(proposed ? new float[]{6, 4} : new float[]{}, 0);
            stream.setLineWidth(Math.max(0.55f, (float) (0.8 + edge.relevance()) * scale));
            stream.moveTo(x1, y1);
            stream.lineTo(x2, y2);
            stream.stroke();
            drawArrow(stream, x1, y1, x2, y2, Math.max(4.0f, 7.0f * scale));
            if (edge.relationType() == null || edge.relationType().isBlank()) continue;
            String state = canonical == null ? DiagramPresentation.relationshipStatus(edge.metadata(), language.german())
                    : DiagramPresentation.canonicalRelationshipStatus(canonical, edge.id());
            float x = (x1 + x2) / 2, y = (y1 + y2) / 2;
            var caption = placeCaption(edge.relationType() + " · " + state, x, y,
                    diagramTop, nodeBounds, captions, fonts.regular(), labelSize);
            if (caption == null) caption = placeCaption(edge.relationType(), x, y,
                    diagramTop, nodeBounds, captions, fonts.regular(), labelSize);
            if (caption != null) captions.add(caption);
        }
        stream.setLineDashPattern(new float[]{}, 0);
        // Dense diagrams identify additional connectors through the complete inventory.
        for (var caption : captions) {
            var rect = caption.bounds();
            stream.setNonStrokingColor(Color.decode(theme.canvasBackground()));
            stream.addRect(rect.x, rect.y, rect.width, rect.height);
            stream.fill();
            text(stream, theme, fonts.regular(), labelSize, rect.x + 4, rect.y + 3, caption.text());
        }
    }

    private static EdgeCaption placeCaption(String label, float x, float y, float diagramTop,
            List<Rectangle2D.Float> nodeBounds, List<EdgeCaption> captions, PDFont font, float labelSize) throws IOException {
        String text = PdfDiagramText.wrap(label, font, labelSize, 210, 1).getFirst();
        float width = PdfDiagramText.width(font, labelSize, text) + 8;
        for (int offset : new int[]{12, -20, 26, -34}) {
            var rect = new Rectangle2D.Float(x - width / 2, y + offset, width, 12);
            if (rect.x < PAGE_MARGIN || rect.getMaxX() > PdfDiagramPages.PAGE_WIDTH - PAGE_MARGIN
                    || rect.y < PAGE_MARGIN || rect.getMaxY() > diagramTop
                    || nodeBounds.stream().anyMatch(rect::intersects)
                    || captions.stream().anyMatch(other -> rect.intersects(other.bounds()))) continue;
            return new EdgeCaption(rect, text);
        }
        return null;
    }

    private static void drawNodes(PDPageContentStream stream,
                                  DiagramScene scene,
                                  float diagramX,
                                  float diagramTop,
                                  float scale, ExportTheme theme, DiagramLanguage language, Fonts fonts, boolean canonical) throws IOException {
        for (DiagramSceneNode node : scene.nodes()) {
            float x = diagramX + (float) node.x() * scale;
            float top = diagramTop - (float) node.y() * scale;
            float width = (float) node.width() * scale;
            float height = (float) node.height() * scale;
            float y = top - height;

            stream.setNonStrokingColor(Color.decode(theme.nodeFill(node.type())));
            stream.addRect(x, y, width, height);
            stream.fill();
            stream.setStrokingColor(Color.decode(theme.nodeBorder(node.type(), node.anchor())));
            stream.setLineWidth(node.anchor() ? Math.max(1.4f, 2.2f * scale) : Math.max(0.7f, scale));
            stream.addRect(x, y, width, height);
            stream.stroke();

            float inset = 8 * scale, small = 8 * scale;
            String score = canonical ? "" : language.score(node);
            float scoreWidth = PdfDiagramText.width(fonts.regular(), small, score);
            String id = PdfDiagramText.wrap(node.id(), fonts.bold(), small,
                    width - 3 * inset - scoreWidth, 1).getFirst();
            text(stream, theme.nodeText(node.type(), "#0f172a"), fonts.bold(), small, x + inset, top - 15 * scale, id);
            text(stream, theme.nodeText(node.type(), "#0f172a"), fonts.regular(), small, x + width - inset - scoreWidth, top - 15 * scale, score);
            var assignment = node.responsibility();
            List<String> labelLines = PdfDiagramText.wrap(node.label(), fonts.regular(), 10 * scale,
                    width - 2 * inset, assignment == null ? 2 : 1);
            for (int index = 0; index < labelLines.size(); index++) {
                text(stream, theme.nodeText(node.type(), "#0f172a"), fonts.regular(), 10 * scale, x + inset,
                        top - (34 + index * 13) * scale, labelLines.get(index));
            }
            if (assignment != null) {
                stream.setNonStrokingColor(Color.decode(assignment.color()));
                stream.addRect(x + inset, y + 32 * scale, 5 * scale, 5 * scale);
                stream.fill();
                text(stream, theme.nodeText(node.type(), "#0f172a"), fonts.regular(), small, x + 2 * inset, y + 32 * scale,
                        PdfDiagramText.wrap(assignment.name(), fonts.regular(), small, width - 3 * inset, 1).getFirst());
            }
            // Two separate footer lines leave the complete taxonomy name room to wrap safely.
            String type = PdfDiagramText.wrap(node.type(), fonts.regular(), small, width - 2 * inset, 1).getFirst();
            text(stream, theme.nodeText(node.type(), "#0f172a"), fonts.regular(), small, x + inset, y + 21 * scale, type);
            if (!canonical || node.container()) {
                String depth = canonical ? "Container" : language.depth(node)
                        + (node.container() ? " | Container" : "");
                text(stream, theme.nodeText(node.type(), "#0f172a"), fonts.regular(), small, x + inset, y + 9 * scale,
                        PdfDiagramText.wrap(depth, fonts.regular(), small, width - 2 * inset, 1).getFirst());
            }
        }
    }

    private static void drawFooter(PDPageContentStream stream, Decoration decoration, float pageWidth,
                                   ExportTheme theme, DiagramLanguage language, PDFont font) throws IOException {
        text(stream, theme, font, 7, PAGE_MARGIN, 17, decoration.footer().getFirst());
        if (!decoration.warnings().isEmpty()) {
            String prefix = language.text("Warnings: ", "Hinweise: ");
            String caption = PdfDiagramText.printable(prefix + String.join(" | ", decoration.warnings()), font);
            if (PdfDiagramText.width(font, 7, caption) > pageWidth - 2 * PAGE_MARGIN)
                caption = prefix + decoration.warnings().size()
                        + language.text(" | See export inventory.", " | Siehe Exportinventar.");
            text(stream, theme, font, 7, PAGE_MARGIN, 8, caption);
        }
    }

    private static void drawArrow(PDPageContentStream stream,
                                  float x1,
                                  float y1,
                                  float x2,
                                  float y2,
                                  float size) throws IOException {
        double angle = Math.atan2(y2 - y1, x2 - x1);
        float ax1 = (float) (x2 - size * Math.cos(angle - Math.PI / 6.0));
        float ay1 = (float) (y2 - size * Math.sin(angle - Math.PI / 6.0));
        float ax2 = (float) (x2 - size * Math.cos(angle + Math.PI / 6.0));
        float ay2 = (float) (y2 - size * Math.sin(angle + Math.PI / 6.0));
        stream.moveTo(x2, y2);
        stream.lineTo(ax1, ay1);
        stream.moveTo(x2, y2);
        stream.lineTo(ax2, ay2);
        stream.stroke();
    }

    private static void text(PDPageContentStream stream, ExportTheme theme,
                             PDFont font, float size, float x, float y, String value) throws IOException {
        text(stream, theme.canvasText("#0f172a"), font, size, x, y, value);
    }

    private static void text(PDPageContentStream stream, String color,
                             PDFont font,
                             float size,
                             float x,
                             float y,
                             String value) throws IOException {
        if (value == null || value.isBlank()) return;
        String printable = PdfDiagramText.printable(value, font);
        stream.beginText();
        stream.setNonStrokingColor(Color.decode(color));
        stream.setFont(font, Math.max(5.0f, size));
        stream.newLineAtOffset(x, y);
        stream.showText(printable);
        stream.endText();
    }

    private record Entry(String identity, List<String> lines) { }

    /** The vector pages are followed by a complete, paginated inventory of the exported evidence. */
    private static void drawInventory(PDDocument document, DiagramScene scene, Map<String, Integer> nodePages,
                                      Map<String, String> nodeReasons, Map<String, Map<String, String>> relationReasons,
                                      ExportTheme theme, DiagramLanguage language, Fonts fonts, Decoration decoration) throws IOException {
        var canonical = decoration.canonical();
        var entries = new ArrayList<Entry>();
        entries.add(new Entry(language.text("Export summary", "Exportübersicht"),
                DiagramPresentation.inventorySummary(scene, language, canonical)));
        var warnings = decoration.warnings();
        for (int i = 0; i < warnings.size(); i++) {
            String identity = language.text("Recorded warning ", "Gespeicherter Hinweis ") + (i + 1);
            entries.add(new Entry(identity, i == 0 ? List.of(identity, warnings.get(i)) : List.of(warnings.get(i))));
        }
        var seenNodes = new HashSet<String>();
        for (var node : scene.nodes()) if (seenNodes.add(node.id())) {
            var lines = new ArrayList<String>();
            if (seenNodes.size() == 1) lines.add(language.text("Element inventory", "Elementinventar"));
            String metrics = canonical == null ? " | " + language.depth(node) + " | " + language.score(node) : "";
            lines.add(node.id() + " | D" + nodePages.get(node.id()) + metrics + (node.container() ? " | Container" : ""));
            lines.add(node.label());
            lines.add(node.type() + language.text(" | Parent: ", " | Übergeordnet: ")
                    + (node.parentId() == null ? language.text("not included", "nicht enthalten") : node.parentId()));
            lines.addAll(DiagramPresentation.responsibilityDetails(node.responsibility(), language));
            String reason = nodeReasons.get(node.id());
            if (reason != null && !reason.isBlank()) lines.add(language.text("Explanation: ", "Begründung: ") + reason);
            entries.add(new Entry(node.id(), lines));
        }
        var seenEdges = new HashSet<String>();
        for (var edge : scene.edges()) if (seenEdges.add(edge.id())) {
            var lines = new ArrayList<String>();
            if (seenEdges.size() == 1) lines.add(language.text("Relationship inventory", "Beziehungsinventar"));
            String metrics = canonical == null ? " | " + Objects.toString(edge.relationCategory(), "n/a")
                    + " | " + language.percent(edge.relevance()) : "";
            lines.add(edge.id() + " | " + edge.sourceId() + " -> " + edge.targetId() + " | " + edge.relationType()
                    + metrics + " | D" + nodePages.get(edge.sourceId()) + " -> D" + nodePages.get(edge.targetId()));
            if (canonical == null) {
                lines.addAll(DiagramPresentation.relationshipDetails(edge.metadata(), language.german()));
                lines.addAll(DiagramPresentation.relationshipEvidenceDetails(relationReasons.get(edge.id()), language.german()));
            } else {
                lines.addAll(DiagramPresentation.canonicalRelationshipDetails(canonical, edge.id()));
            }
            entries.add(new Entry(edge.id(), lines));
        }
        if (scene.edges().isEmpty()) entries.add(new Entry(language.text("Relationship inventory", "Beziehungsinventar"),
                List.of(language.text("Relationship inventory | No relationships in this export.", "Beziehungsinventar | Keine Beziehungen in diesem Export."))));
        float width = PDRectangle.A4.getWidth() - 2 * PAGE_MARGIN;
        int capacity = (int) ((PDRectangle.A4.getHeight() - 2 * PAGE_MARGIN - 36) / 13);
        var pages = inventoryPages(entries, fonts.regular(), width, capacity, language);
        for (int index = 0; index < pages.size(); index++) {
            var page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                background(stream, theme, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                float top = PDRectangle.A4.getHeight() - PAGE_MARGIN;
                text(stream, theme, fonts.bold(), 13, PAGE_MARGIN, top - 13,
                        language.text("Export inventory - ", "Exportinventar - ") + (index + 1));
                var lines = pages.get(index);
                for (int row = 0; row < lines.size(); row++)
                    text(stream, theme, fonts.regular(), 9, PAGE_MARGIN, top - 36 - row * 13, lines.get(row));
            }
        }
    }

    /** A short entry moves with its complete evidence. A very long entry repeats its ID on later pages. */
    private static List<List<String>> inventoryPages(List<Entry> entries, PDFont font, float width,
                                                     int capacity, DiagramLanguage language) throws IOException {
        var pages = new ArrayList<List<String>>();
        List<String> page = new ArrayList<>();
        pages.add(page);
        for (var entry : entries) {
            var lines = new ArrayList<String>();
            for (String line : entry.lines()) lines.addAll(PdfDiagramText.wrapParagraphs(line, font, 9, width));
            int available = capacity - page.size();
            if (!page.isEmpty() && lines.size() > available && (lines.size() <= capacity / 2 || available < 8)) {
                page = new ArrayList<>();
                pages.add(page);
            }
            for (String line : lines) {
                if (page.size() == capacity) {
                    page = new ArrayList<>();
                    pages.add(page);
                    page.add(PdfDiagramText.wrap(entry.identity() + language.text(" (continued)", " (Fortsetzung)"),
                            font, 9, width, 1).getFirst());
                }
                page.add(line);
            }
            if (page.size() < capacity) page.add("");
        }
        return pages;
    }

    private static String abbreviated(String value) {
        String normalized = safe(value);
        return normalized.length() > 12 ? normalized.substring(0, 12) : normalized;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "n/a" : value.strip();
    }

}
