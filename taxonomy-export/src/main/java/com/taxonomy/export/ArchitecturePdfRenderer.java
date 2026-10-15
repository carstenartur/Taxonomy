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
import java.util.function.Consumer;

/** Draws a vector PDF directly from the same deterministic scene returned to other export adapters. */
public final class ArchitecturePdfRenderer {

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
        requireScene(scene);
        if (metadata == null) {
            throw new IllegalArgumentException("Architecture snapshot metadata is required");
        }
        return renderScene(scene, document -> configureMetadata(document, scene, metadata),
                (stream, height) -> drawHeader(stream, scene, metadata, height),
                stream -> drawFooter(stream, metadata));
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
        }, (stream, height) -> {
            text(stream, BOLD, 16, PAGE_MARGIN, height - PAGE_MARGIN - 16, scene.title());
            List<String> lines = Arrays.asList(displayProvenance.split("\n"));
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                text(stream, REGULAR, 8, PAGE_MARGIN, height - PAGE_MARGIN - 33 - i * 12, lines.get(i));
            }
        }, stream -> text(stream, REGULAR, 8, PAGE_MARGIN, 16,
                "Derived server layout · Git-authoritative architecture"));
    }

    private static void requireScene(DiagramScene scene) {
        if (scene == null || scene.isEmpty()) {
            throw new IllegalArgumentException("Architecture scene must not be empty");
        }
    }

    @FunctionalInterface
    private interface HeaderWriter {
        void write(PDPageContentStream stream, float height) throws IOException;
    }

    @FunctionalInterface
    private interface FooterWriter {
        void write(PDPageContentStream stream) throws IOException;
    }

    private byte[] renderScene(DiagramScene scene, Consumer<PDDocument> metadata,
                               HeaderWriter header, FooterWriter footer) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            // Preserve natural-size labels on wide architecture views. A3 remains the minimum;
            // larger scenes use a poster page, bounded by the PDF page-size limit.
            float pageWidth = (float) Math.min(MAX_PAGE_SIZE,
                    Math.max(PDRectangle.A3.getHeight(), scene.width() + 2 * PAGE_MARGIN));
            float pageHeight = (float) Math.min(MAX_PAGE_SIZE,
                    Math.max(PDRectangle.A3.getWidth(), scene.height() + 2 * PAGE_MARGIN + HEADER_HEIGHT));
            PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
            document.addPage(page);
            metadata.accept(document);

            float availableWidth = page.getMediaBox().getWidth() - 2 * PAGE_MARGIN;
            float availableHeight = page.getMediaBox().getHeight() - 2 * PAGE_MARGIN - HEADER_HEIGHT;
            float scale = (float) Math.min(1.0, Math.min(
                    availableWidth / Math.max(1.0, scene.width()),
                    availableHeight / Math.max(1.0, scene.height())));
            if (scale < 0.8f) throw new IllegalArgumentException(
                    "Architecture exceeds readable PDF page size; export a smaller selection or separate taxonomies");
            float diagramX = PAGE_MARGIN;
            float diagramTop = page.getMediaBox().getHeight() - PAGE_MARGIN - HEADER_HEIGHT;

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.setNonStrokingColor(Color.WHITE);
                stream.addRect(0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                stream.fill();

                header.write(stream, page.getMediaBox().getHeight());
                drawEdges(stream, scene, diagramX, diagramTop, scale);
                drawNodes(stream, scene, diagramX, diagramTop, scale);
                footer.write(stream);
            }

            drawInventory(document, scene);
            document.save(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to render architecture PDF", error);
        }
    }

    private static void configureMetadata(PDDocument document, DiagramScene scene, SnapshotMetadata metadata) {
        PDDocumentInformation information = new PDDocumentInformation();
        information.setTitle(scene.title());
        information.setSubject("Requirement-derived architecture snapshot " + metadata.snapshotId());
        information.setAuthor("Taxonomy Architecture Analyzer");
        information.setCreator("Taxonomy server-side vector renderer");
        information.setKeywords("architecture, taxonomy, requirement, snapshot, " + metadata.snapshotId());
        document.setDocumentInformation(information);
    }

    private static void drawHeader(PDPageContentStream stream,
                                   DiagramScene scene,
                                   SnapshotMetadata metadata,
                                   float pageHeight) throws IOException {
        float y = pageHeight - PAGE_MARGIN - 16;
        text(stream, BOLD, 16, PAGE_MARGIN, y, scene.title());
        text(stream, REGULAR, 9, PAGE_MARGIN, y - 19,
                "Project " + metadata.projectKey()
                        + " · Requirement " + metadata.requirementKey()
                        + " · Snapshot " + metadata.snapshotId());
        text(stream, REGULAR, 8, PAGE_MARGIN, y - 33,
                "Provider " + safe(metadata.provider())
                        + " · Branch " + safe(metadata.branchName())
                        + " · Commit " + abbreviated(metadata.commitSha()));
    }

    private static void drawEdges(PDPageContentStream stream,
                                  DiagramScene scene,
                                  float diagramX,
                                  float diagramTop,
                                  float scale) throws IOException {
        stream.setStrokingColor(new Color(88, 97, 116));
        for (DiagramSceneEdge edge : scene.edges()) {
            float x1 = diagramX + (float) edge.sourceX() * scale;
            float y1 = diagramTop - (float) edge.sourceY() * scale;
            float x2 = diagramX + (float) edge.targetX() * scale;
            float y2 = diagramTop - (float) edge.targetY() * scale;
            stream.setLineWidth(Math.max(0.55f, (float) (0.8 + edge.relevance()) * scale));
            stream.moveTo(x1, y1);
            stream.lineTo(x2, y2);
            stream.stroke();
            drawArrow(stream, x1, y1, x2, y2, Math.max(4.0f, 7.0f * scale));
            if (edge.relationType() != null && !edge.relationType().isBlank()) {
                text(stream, REGULAR, Math.max(5.5f, 7.5f * scale),
                        (x1 + x2) / 2.0f - 12,
                        (y1 + y2) / 2.0f + 4,
                        edge.relationType());
            }
        }
    }

    private static void drawNodes(PDPageContentStream stream,
                                  DiagramScene scene,
                                  float diagramX,
                                  float diagramTop,
                                  float scale) throws IOException {
        for (DiagramSceneNode node : scene.nodes()) {
            float x = diagramX + (float) node.x() * scale;
            float top = diagramTop - (float) node.y() * scale;
            float width = (float) node.width() * scale;
            float height = (float) node.height() * scale;
            float y = top - height;

            stream.setNonStrokingColor(Color.decode(DiagramPresentation.fill(node.type())));
            stream.addRect(x, y, width, height);
            stream.fill();
            stream.setStrokingColor(node.anchor()
                    ? new Color(23, 37, 84)
                    : new Color(100, 116, 139));
            stream.setLineWidth(node.anchor() ? Math.max(1.4f, 2.2f * scale) : Math.max(0.7f, scale));
            stream.addRect(x, y, width, height);
            stream.stroke();

            float inset = 8 * scale, small = 8 * scale;
            String score = DiagramPresentation.percent(node.relevance());
            float scoreWidth = PdfDiagramText.width(REGULAR, small, score);
            String id = PdfDiagramText.wrap(node.id(), BOLD, small,
                    width - 3 * inset - scoreWidth, 1).getFirst();
            text(stream, BOLD, small, x + inset, top - 15 * scale, id);
            text(stream, REGULAR, small, x + width - inset - scoreWidth, top - 15 * scale, score);
            List<String> labelLines = PdfDiagramText.wrap(node.label(), REGULAR, 10 * scale,
                    width - 2 * inset, 2);
            for (int index = 0; index < labelLines.size(); index++) {
                text(stream, REGULAR, 10 * scale, x + inset,
                        top - (34 + index * 13) * scale, labelLines.get(index));
            }
            // Two separate footer lines leave the complete taxonomy name room to wrap safely.
            String type = PdfDiagramText.wrap(node.type(), REGULAR, small, width - 2 * inset, 1).getFirst();
            text(stream, REGULAR, small, x + inset, y + 21 * scale, type);
            String depth = "Depth " + node.depth() + (node.container() ? " | Container" : "");
            text(stream, REGULAR, small, x + inset, y + 9 * scale,
                    PdfDiagramText.wrap(depth, REGULAR, small, width - 2 * inset, 1).getFirst());
        }
    }

    private static void drawFooter(PDPageContentStream stream, SnapshotMetadata metadata) throws IOException {
        text(stream, REGULAR, 7, PAGE_MARGIN, 17,
                "Generated from persisted architecture snapshot; no page screenshot and no LLM re-analysis.");
        if (!metadata.warnings().isEmpty()) {
            text(stream, REGULAR, 7, PAGE_MARGIN, 8,
                    "Warnings: " + String.join(" | ", metadata.warnings()));
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

    private static void text(PDPageContentStream stream,
                             PDType1Font font,
                             float size,
                             float x,
                             float y,
                             String value) throws IOException {
        String printable = ascii(value);
        if (printable.isBlank()) return;
        stream.beginText();
        stream.setNonStrokingColor(new Color(15, 23, 42));
        stream.setFont(font, Math.max(5.0f, size));
        stream.newLineAtOffset(x, y);
        stream.showText(printable);
        stream.endText();
    }

    /** A complete, paginated inventory is the accessible counterpart of bounded diagram labels. */
    private static void drawInventory(PDDocument document, DiagramScene scene) throws IOException {
        var lines = new java.util.ArrayList<String>(DiagramPresentation.inventorySummary(scene));
        lines.add("Element inventory | diagram ellipses do not remove saved labels below");
        var seenNodes = new java.util.HashSet<String>();
        for (var node : scene.nodes()) if (seenNodes.add(node.id())) {
            lines.add(node.id() + " | Depth " + node.depth() + " | " + DiagramPresentation.percent(node.relevance())
                    + (node.container() ? " | Container" : ""));
            lines.add(node.label());
            lines.add(node.type() + " | Parent: " + (node.parentId() == null ? "not included" : node.parentId()));
        }
        lines.add("Relationship inventory | presence does not establish human confirmation");
        var seenEdges = new java.util.HashSet<String>();
        for (var edge : scene.edges()) if (seenEdges.add(edge.id()))
            lines.add(edge.id() + " | " + edge.sourceId() + " -> " + edge.targetId()
                    + " | " + edge.relationType() + " | " + edge.relationCategory());
        var wrapped = new java.util.ArrayList<String>();
        float width = PDRectangle.A4.getWidth() - 2 * PAGE_MARGIN;
        for (String line : lines) wrapped.addAll(PdfDiagramText.wrap(line, REGULAR, 9, width, Integer.MAX_VALUE));
        int perPage = (int) ((PDRectangle.A4.getHeight() - 2 * PAGE_MARGIN - 36) / 13);
        for (int start = 0; start < wrapped.size(); start += perPage) {
            var page = new PDPage(PDRectangle.A4); document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                float top = PDRectangle.A4.getHeight() - PAGE_MARGIN;
                text(stream, BOLD, 13, PAGE_MARGIN, top - 13, "Export inventory - " + (start / perPage + 1));
                for (int i = start; i < Math.min(start + perPage, wrapped.size()); i++)
                    text(stream, REGULAR, 9, PAGE_MARGIN, top - 36 - (i - start) * 13, wrapped.get(i));
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
