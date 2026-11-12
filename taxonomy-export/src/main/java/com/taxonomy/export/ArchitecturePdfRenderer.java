package com.taxonomy.export;

import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.diagram.DiagramSceneEdge;
import com.taxonomy.diagram.DiagramSceneNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Draws a vector PDF directly from the same deterministic scene returned to other export adapters. */
public final class ArchitecturePdfRenderer {

    private final ExportThemeProvider themes;

    public ArchitecturePdfRenderer() { this(ExportThemeProvider.defaults()); }

    public ArchitecturePdfRenderer(ExportThemeProvider themes) {
        this.themes = java.util.Objects.requireNonNull(themes, "themes");
    }

    private static final float PAGE_MARGIN = 32.0f;
    private static final float HEADER_HEIGHT = 54.0f;
    private static final float MAX_PAGE_SIZE = 14_400.0f;
    private static final PDType1Font REGULAR =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    /** Neutral metadata required to decorate a persisted architecture snapshot export. */
    public record SnapshotMetadata(
            String projectKey,
            String requirementKey,
            String snapshotId,
            String provider,
            String branchName,
            String commitSha,
            List<String> warnings) {

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
        if (metadata == null) {
            throw new IllegalArgumentException("Architecture snapshot metadata is required");
        }
        return renderScene(scene, document -> configureMetadata(document, scene, metadata, language),
                (stream, height, theme) -> drawHeader(stream, scene, metadata, height, theme, language),
                (stream, width, theme) -> drawFooter(stream, metadata, width, theme, language),
                nodeReasons, relationReasons, language, metadata.warnings());
    }

    /** Live editor export uses the same vector geometry without masquerading as an analysis snapshot. */
    public byte[] render(DiagramScene scene, String provenance) {
        requireScene(scene);
        String displayProvenance = safe(provenance);
        return renderScene(scene, document -> {
            PDDocumentInformation information = new PDDocumentInformation();
            information.setTitle(scene.title());
            information.setSubject(provenance);
            information.setCreator("Taxonomy server-side vector renderer");
            document.setDocumentInformation(information);
        }, (stream, height, theme) -> {
            text(stream, theme, BOLD, 16, PAGE_MARGIN, height - PAGE_MARGIN - 16, scene.title());
            List<String> lines = Arrays.asList(displayProvenance.split("\n"));
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                text(stream, theme, REGULAR, 8, PAGE_MARGIN, height - PAGE_MARGIN - 33 - i * 12, lines.get(i));
            }
        }, (stream, width, theme) -> text(stream, theme, REGULAR, 8, PAGE_MARGIN, 16,
                "Derived server layout · Git-authoritative architecture"), Map.of(), Map.of(), DiagramLanguage.ENGLISH, List.of());
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
        return renderScene(scene, document -> {
            var information = new PDDocumentInformation();
            information.setTitle(scene.title());
            information.setSubject("Client-provided working view");
            information.setCreator("Taxonomy server-side vector renderer");
            document.setDocumentInformation(information);
        }, (stream, height, theme) -> {
            text(stream, theme, BOLD, 16, PAGE_MARGIN, height - PAGE_MARGIN - 16,
                    "Requirement architecture - working view");
            text(stream, theme, REGULAR, 8, PAGE_MARGIN, height - PAGE_MARGIN - 33,
                    "Client-provided working view; source authority has not been verified.");
            text(stream, theme, REGULAR, 8, PAGE_MARGIN, height - PAGE_MARGIN - 45,
                    PdfDiagramText.wrap(scene.title(), REGULAR, 8,
                            PDRectangle.A3.getHeight() - 2 * PAGE_MARGIN, 1).getFirst());
        }, (stream, width, theme) -> text(stream, theme, REGULAR, 8, PAGE_MARGIN, 16,
                "Generated server layout | Client-provided working view | No re-analysis or persistence"), nodeReasons, relationReasons, DiagramLanguage.ENGLISH, List.of());
    }

    private static void requireScene(DiagramScene scene) {
        if (scene == null || scene.isEmpty()) {
            throw new IllegalArgumentException("Architecture scene must not be empty");
        }
    }

    @FunctionalInterface
    private interface HeaderWriter {
        void write(PDPageContentStream stream, float height, ExportTheme theme) throws IOException;
    }

    @FunctionalInterface
    private interface FooterWriter {
        void write(PDPageContentStream stream, float pageWidth, ExportTheme theme) throws IOException;
    }

    private byte[] renderScene(DiagramScene scene, Consumer<PDDocument> metadata,
                               HeaderWriter header, FooterWriter footer, Map<String, String> nodeReasons,
                               Map<String, Map<String, String>> relationReasons, DiagramLanguage language, List<String> warnings) {
        ExportTheme theme = java.util.Objects.requireNonNull(themes.current(), "export theme");
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfDiagramPages.Plan plan = PdfDiagramPages.plan(scene);
            metadata.accept(document);
            document.getDocumentCatalog().setLanguage(language.tag());
            for (int index = 0; index < plan.panels().size(); index++) {
                var panel = plan.panels().get(index);
                DiagramScene part = panel.scene();
                float pageWidth = (float) Math.min(MAX_PAGE_SIZE,
                        Math.max(PDRectangle.A3.getHeight(), part.width() + 2 * PAGE_MARGIN));
                float pageHeight = (float) Math.min(MAX_PAGE_SIZE,
                        Math.max(PDRectangle.A3.getWidth(), part.height() + 2 * PAGE_MARGIN + HEADER_HEIGHT));
                PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
                document.addPage(page);
                float scale = (float) Math.min(1.0, Math.min(
                        (pageWidth - 2 * PAGE_MARGIN) / Math.max(1.0, part.width()),
                        (pageHeight - 2 * PAGE_MARGIN - HEADER_HEIGHT) / Math.max(1.0, part.height())));
                if (scale < 0.8f) throw new IllegalArgumentException("PDF panel is not readable");
                float diagramTop = pageHeight - PAGE_MARGIN - HEADER_HEIGHT;
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.setNonStrokingColor(Color.decode(theme.canvasBackground()));
                    stream.addRect(0, 0, pageWidth, pageHeight);
                    stream.fill();
                    header.write(stream, pageHeight, theme);
                    drawEdges(stream, part, PAGE_MARGIN, diagramTop, scale, theme, language);
                    drawNodes(stream, part, PAGE_MARGIN, diagramTop, scale, theme, language);
                    footer.write(stream, pageWidth, theme);
                    if (plan.panels().size() > 1) {
                        text(stream, theme, REGULAR, 8, PAGE_MARGIN, 30,
                                language.text("Diagram D", "Diagramm D") + (index + 1) + "/" + plan.panels().size()
                                        + language.text(" | Cross-page relationships: ", " | Seitenübergreifende Beziehungen: ") + panel.crossPageRelationships()
                                        + language.text(" | Complete endpoints and diagram references: see export inventory.", " | Endpunkte und Diagrammverweise siehe Exportinventar."));
                    }
                }
            }
            drawInventory(document, scene, plan.nodePages(), nodeReasons == null ? Map.of() : nodeReasons,
                    relationReasons == null ? Map.of() : relationReasons, theme, language, warnings);
            document.save(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to render architecture PDF", error);
        }
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

    private static void drawHeader(PDPageContentStream stream,
                                   DiagramScene scene,
                                   SnapshotMetadata metadata,
                                   float pageHeight, ExportTheme theme, DiagramLanguage language) throws IOException {
        float y = pageHeight - PAGE_MARGIN - 16;
        text(stream, theme, BOLD, 16, PAGE_MARGIN, y, scene.title());
        text(stream, theme, REGULAR, 9, PAGE_MARGIN, y - 19,
                language.text("Project ", "Projekt ") + metadata.projectKey()
                        + language.text(" · Requirement ", " · Anforderung ") + metadata.requirementKey()
                        + " · Snapshot " + metadata.snapshotId());
        text(stream, theme, REGULAR, 8, PAGE_MARGIN, y - 33,
                language.text("Provider ", "Anbieter ") + safe(metadata.provider())
                        + " · Branch " + safe(metadata.branchName())
                        + " · Commit " + abbreviated(metadata.commitSha()));
    }

    private static void drawEdges(PDPageContentStream stream,
                                  DiagramScene scene,
                                  float diagramX,
                                  float diagramTop,
                                  float scale, ExportTheme theme, DiagramLanguage language) throws IOException {
        stream.setStrokingColor(new Color(88, 97, 116));
        for (DiagramSceneEdge edge : scene.edges()) {
            float x1 = diagramX + (float) edge.sourceX() * scale;
            float y1 = diagramTop - (float) edge.sourceY() * scale;
            float x2 = diagramX + (float) edge.targetX() * scale;
            float y2 = diagramTop - (float) edge.targetY() * scale;
            boolean proposed = edge.metadata() != null && edge.metadata().proposed();
            stream.setStrokingColor(Color.decode(theme.relationshipStroke(edge.relationType(), proposed)));
            stream.setLineDashPattern(proposed ? new float[]{6, 4} : new float[]{}, 0);
            stream.setLineWidth(Math.max(0.55f, (float) (0.8 + edge.relevance()) * scale));
            stream.moveTo(x1, y1);
            stream.lineTo(x2, y2);
            stream.stroke();
            drawArrow(stream, x1, y1, x2, y2, Math.max(4.0f, 7.0f * scale));
            if (edge.relationType() != null && !edge.relationType().isBlank()) {
                float size = Math.max(5.5f, 7.5f * scale);
                float captionWidth = Math.max(12, Math.min(210 * scale, Math.abs(x2 - x1) - 12 * scale));
                String relation = PdfDiagramText.wrap(edge.relationType(), REGULAR, size, captionWidth, 1).getFirst();
                String status = PdfDiagramText.wrap(DiagramPresentation.relationshipStatus(edge.metadata(), language.german()), REGULAR, size, captionWidth, 1).getFirst();
                text(stream, theme, REGULAR, size, (x1 + x2 - PdfDiagramText.width(REGULAR, size, relation)) / 2,
                        (y1 + y2) / 2 + 6, relation);
                text(stream, theme, REGULAR, size, (x1 + x2 - PdfDiagramText.width(REGULAR, size, status)) / 2,
                        (y1 + y2) / 2 - 6, status);
            }
        }
        stream.setLineDashPattern(new float[]{}, 0);
    }

    private static void drawNodes(PDPageContentStream stream,
                                  DiagramScene scene,
                                  float diagramX,
                                  float diagramTop,
                                  float scale, ExportTheme theme, DiagramLanguage language) throws IOException {
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
            String score = language.percent(node.relevance());
            float scoreWidth = PdfDiagramText.width(REGULAR, small, score);
            String id = PdfDiagramText.wrap(node.id(), BOLD, small,
                    width - 3 * inset - scoreWidth, 1).getFirst();
            text(stream, theme.nodeText(node.type(), "#0f172a"), BOLD, small, x + inset, top - 15 * scale, id);
            text(stream, theme.nodeText(node.type(), "#0f172a"), REGULAR, small, x + width - inset - scoreWidth, top - 15 * scale, score);
            List<String> labelLines = PdfDiagramText.wrap(node.label(), REGULAR, 10 * scale,
                    width - 2 * inset, 2);
            for (int index = 0; index < labelLines.size(); index++) {
                text(stream, theme.nodeText(node.type(), "#0f172a"), REGULAR, 10 * scale, x + inset,
                        top - (34 + index * 13) * scale, labelLines.get(index));
            }
            // Two separate footer lines leave the complete taxonomy name room to wrap safely.
            String type = PdfDiagramText.wrap(node.type(), REGULAR, small, width - 2 * inset, 1).getFirst();
            text(stream, theme.nodeText(node.type(), "#0f172a"), REGULAR, small, x + inset, y + 21 * scale, type);
            String depth = language.text("Depth ", "Tiefe ") + node.depth() + (node.container() ? " | Container" : "");
            text(stream, theme.nodeText(node.type(), "#0f172a"), REGULAR, small, x + inset, y + 9 * scale,
                    PdfDiagramText.wrap(depth, REGULAR, small, width - 2 * inset, 1).getFirst());
        }
    }

    private static void drawFooter(PDPageContentStream stream, SnapshotMetadata metadata, float pageWidth,
                                   ExportTheme theme, DiagramLanguage language) throws IOException {
        text(stream, theme, REGULAR, 7, PAGE_MARGIN, 17,
                language.text("Generated from persisted architecture snapshot; no page screenshot and no LLM re-analysis.", "Aus dem gespeicherten Architektursnapshot erzeugt. Keine Bildschirmaufnahme und keine erneute KI-Analyse."));
        if (!metadata.warnings().isEmpty()) {
            String caption = language.text("Warnings: ", "Hinweise: ") + String.join(" | ", metadata.warnings());
            if (PdfDiagramText.width(REGULAR, 7, PdfDiagramText.printable(caption)) > pageWidth - 2 * PAGE_MARGIN) {
                caption = language.text("Warnings: ", "Hinweise: ") + metadata.warnings().size()
                        + language.text(" | See export inventory.", " | Siehe Exportinventar.");
            }
            text(stream, theme, REGULAR, 7, PAGE_MARGIN, 8, caption);
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
                             PDType1Font font, float size, float x, float y, String value) throws IOException {
        text(stream, theme.canvasText("#0f172a"), font, size, x, y, value);
    }

    private static void text(PDPageContentStream stream, String color,
                             PDType1Font font,
                             float size,
                             float x,
                             float y,
                             String value) throws IOException {
        String printable = ascii(value);
        if (printable.isBlank()) return;
        stream.beginText();
        stream.setNonStrokingColor(Color.decode(color));
        stream.setFont(font, Math.max(5.0f, size));
        stream.newLineAtOffset(x, y);
        stream.showText(printable);
        stream.endText();
    }

    /** A complete, paginated inventory is the accessible counterpart of bounded diagram labels. */
    private static void drawInventory(PDDocument document, DiagramScene scene, Map<String, Integer> nodePages,
                                      Map<String, String> nodeReasons,
                                      Map<String, Map<String, String>> relationReasons, ExportTheme theme,
                                      DiagramLanguage language, List<String> warnings) throws IOException {
        var lines = new java.util.ArrayList<String>(DiagramPresentation.inventorySummary(scene, language));
        if (!warnings.isEmpty()) {
            lines.add(language.text("Recorded warnings", "Gespeicherte Hinweise"));
            lines.addAll(warnings);
        }
        lines.add(language.text("Element inventory | diagram ellipses do not remove saved labels below", "Elementinventar | hier stehen die vollständigen gespeicherten Bezeichnungen"));
        var seenNodes = new java.util.HashSet<String>();
        for (var node : scene.nodes()) if (seenNodes.add(node.id())) {
            lines.add(node.id() + " | D" + nodePages.get(node.id()) + language.text(" | Depth ", " | Tiefe ") + node.depth() + " | " + language.percent(node.relevance())
                    + (node.container() ? " | Container" : ""));
            lines.add(node.label());
            lines.add(node.type() + language.text(" | Parent: ", " | Übergeordnet: ") + (node.parentId() == null ? language.text("not included", "nicht enthalten") : node.parentId()));
            String reason = nodeReasons.get(node.id());
            if (reason != null && !reason.isBlank()) lines.add(language.text("Explanation: ", "Begründung: ") + reason);
        }
        lines.add(language.text("Relationship inventory | presence does not establish human confirmation", "Beziehungsinventar | enthaltene Beziehungen belegen keine menschliche Bestätigung"));
        var seenEdges = new java.util.HashSet<String>();
        for (var edge : scene.edges()) if (seenEdges.add(edge.id())) {
            lines.add(edge.id() + " | " + edge.sourceId() + " -> " + edge.targetId()
                    + " | " + edge.relationType() + " | " + edge.relationCategory()
                    + " | " + language.percent(edge.relevance())
                    + " | D" + nodePages.get(edge.sourceId()) + " -> D" + nodePages.get(edge.targetId()));
            lines.addAll(DiagramPresentation.relationshipDetails(edge.metadata(), language.german()));
            lines.addAll(DiagramPresentation.relationshipEvidenceDetails(relationReasons.get(edge.id()), language.german()));
        }
        var wrapped = new java.util.ArrayList<String>();
        float width = PDRectangle.A4.getWidth() - 2 * PAGE_MARGIN;
        for (String line : lines) wrapped.addAll(PdfDiagramText.wrap(line, REGULAR, 9, width, Integer.MAX_VALUE));
        int perPage = (int) ((PDRectangle.A4.getHeight() - 2 * PAGE_MARGIN - 36) / 13);
        for (int start = 0; start < wrapped.size(); start += perPage) {
            var page = new PDPage(PDRectangle.A4); document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                stream.setNonStrokingColor(Color.decode(theme.canvasBackground()));
                stream.addRect(0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                stream.fill();
                float top = PDRectangle.A4.getHeight() - PAGE_MARGIN;
                text(stream, theme, BOLD, 13, PAGE_MARGIN, top - 13, language.text("Export inventory - ", "Exportinventar - ") + (start / perPage + 1));
                for (int i = start; i < Math.min(start + perPage, wrapped.size()); i++)
                    text(stream, theme, REGULAR, 9, PAGE_MARGIN, top - 36 - (i - start) * 13, wrapped.get(i));
            }
        }
    }

    private static String abbreviated(String value) {
        String normalized = safe(value);
        return normalized.length() > 12 ? normalized.substring(0, 12) : normalized;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "n/a" : value.strip();
    }

    private static String ascii(String value) {
        return PdfDiagramText.printable(value);
    }
}
