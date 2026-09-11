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
import java.text.Normalizer;
import java.util.List;
import java.util.Objects;

/** Produces a vector PDF from a render-ready diagram scene without application-context dependencies. */
public final class PdfDiagramRenderer {

    public static final String CREATOR = "Taxonomy server-side vector renderer";

    private static final float PAGE_MARGIN = 32.0f;
    private static final float HEADER_HEIGHT = 54.0f;
    private static final PDType1Font REGULAR =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    /** Caller-owned document semantics; the renderer owns only vector/PDF mechanics. */
    public record DocumentDetails(String subject, String author, String keywords,
                                  List<HeaderLine> headerLines, List<FooterLine> footerLines) {
        public DocumentDetails {
            headerLines = List.copyOf(Objects.requireNonNull(headerLines, "headerLines"));
            footerLines = List.copyOf(Objects.requireNonNull(footerLines, "footerLines"));
        }
    }

    /** Regular-font header line positioned relative to the fixed diagram title baseline. */
    public record HeaderLine(String text, float fontSize, float offsetFromTitle) {
        public HeaderLine {
            Objects.requireNonNull(text, "text");
            if (!Float.isFinite(fontSize) || fontSize <= 0) {
                throw new IllegalArgumentException("Header font size must be positive and finite");
            }
            if (!Float.isFinite(offsetFromTitle) || offsetFromTitle < 0) {
                throw new IllegalArgumentException("Header offset must be non-negative and finite");
            }
        }
    }

    /** Regular-font footer line positioned from the bottom of the page. */
    public record FooterLine(String text, float fontSize, float yFromBottom) {
        public FooterLine {
            Objects.requireNonNull(text, "text");
            if (!Float.isFinite(fontSize) || fontSize <= 0) {
                throw new IllegalArgumentException("Footer font size must be positive and finite");
            }
            if (!Float.isFinite(yFromBottom) || yFromBottom < 0) {
                throw new IllegalArgumentException("Footer position must be non-negative and finite");
            }
        }
    }

    public byte[] render(DiagramScene scene, DocumentDetails details) {
        if (scene == null || scene.isEmpty()) {
            throw new IllegalArgumentException("Diagram scene must contain at least one node");
        }
        Objects.requireNonNull(details, "details");

        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(PDRectangle.A3.getHeight(), PDRectangle.A3.getWidth()));
            document.addPage(page);
            configureMetadata(document, scene, details);

            float availableWidth = page.getMediaBox().getWidth() - 2 * PAGE_MARGIN;
            float availableHeight = page.getMediaBox().getHeight() - 2 * PAGE_MARGIN - HEADER_HEIGHT;
            float scale = (float) Math.min(
                    availableWidth / Math.max(1.0, scene.width()),
                    availableHeight / Math.max(1.0, scene.height()));
            float diagramX = PAGE_MARGIN;
            float diagramTop = page.getMediaBox().getHeight() - PAGE_MARGIN - HEADER_HEIGHT;

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.setNonStrokingColor(Color.WHITE);
                stream.addRect(0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                stream.fill();

                drawPageText(stream, scene, details, page.getMediaBox().getHeight());
                drawEdges(stream, scene, diagramX, diagramTop, scale);
                drawNodes(stream, scene, diagramX, diagramTop, scale);
            }

            document.save(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to render diagram PDF", error);
        }
    }

    private static void configureMetadata(PDDocument document, DiagramScene scene, DocumentDetails details) {
        PDDocumentInformation information = new PDDocumentInformation();
        information.setTitle(scene.title());
        setIfPresent(details.subject(), information::setSubject);
        setIfPresent(details.author(), information::setAuthor);
        information.setCreator(CREATOR);
        setIfPresent(details.keywords(), information::setKeywords);
        document.setDocumentInformation(information);
    }

    private static void setIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) setter.accept(value.strip());
    }

    private static void drawPageText(PDPageContentStream stream, DiagramScene scene,
                                     DocumentDetails details, float pageHeight) throws IOException {
        float titleY = pageHeight - PAGE_MARGIN - 16;
        text(stream, BOLD, 16, PAGE_MARGIN, titleY, scene.title());
        for (HeaderLine line : details.headerLines()) {
            text(stream, REGULAR, line.fontSize(), PAGE_MARGIN,
                    titleY - line.offsetFromTitle(), line.text());
        }
        for (FooterLine line : details.footerLines()) {
            text(stream, REGULAR, line.fontSize(), PAGE_MARGIN, line.yFromBottom(), line.text());
        }
    }

    private static void drawEdges(PDPageContentStream stream, DiagramScene scene,
                                  float diagramX, float diagramTop, float scale) throws IOException {
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

    private static void drawNodes(PDPageContentStream stream, DiagramScene scene,
                                  float diagramX, float diagramTop, float scale) throws IOException {
        for (DiagramSceneNode node : scene.nodes()) {
            float x = diagramX + (float) node.x() * scale;
            float top = diagramTop - (float) node.y() * scale;
            float width = (float) node.width() * scale;
            float height = (float) node.height() * scale;
            float y = top - height;

            stream.setNonStrokingColor(fill(node.type()));
            stream.addRect(x, y, width, height);
            stream.fill();
            stream.setStrokingColor(node.anchor()
                    ? new Color(23, 37, 84)
                    : new Color(100, 116, 139));
            stream.setLineWidth(node.anchor() ? Math.max(1.4f, 2.2f * scale) : Math.max(0.7f, scale));
            stream.addRect(x, y, width, height);
            stream.stroke();

            float fontScale = Math.max(0.62f, scale);
            text(stream, BOLD, 7.5f * fontScale, x + 8 * scale, top - 15 * scale, node.id());
            text(stream, REGULAR, 6.3f * fontScale,
                    x + width - 62 * scale,
                    top - 14 * scale,
                    node.type() + " " + Math.round(node.relevance() * 100.0) + "%");
            List<String> labelLines = DiagramTextWrapper.wrap(node.label(), 34, 2, "n/a");
            for (int index = 0; index < labelLines.size(); index++) {
                text(stream, REGULAR, 8.3f * fontScale,
                        x + 8 * scale,
                        top - (38 + index * 16) * scale,
                        labelLines.get(index));
            }
        }
    }

    private static void drawArrow(PDPageContentStream stream,
                                  float x1, float y1, float x2, float y2, float size) throws IOException {
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

    private static void text(PDPageContentStream stream, PDType1Font font,
                             float size, float x, float y, String value) throws IOException {
        String safe = ascii(value);
        if (safe.isBlank()) return;
        stream.beginText();
        stream.setNonStrokingColor(new Color(15, 23, 42));
        stream.setFont(font, Math.max(5.0f, size));
        stream.newLineAtOffset(x, y);
        stream.showText(safe);
        stream.endText();
    }

    private static Color fill(String type) {
        if (type == null) return new Color(241, 245, 249);
        return switch (type) {
            case "Capabilities" -> new Color(219, 234, 254);
            case "Business Processes", "Business Roles" -> new Color(220, 252, 231);
            case "Core Services", "COI Services", "Services" -> new Color(254, 243, 199);
            case "User Applications", "Applications" -> new Color(243, 232, 255);
            case "Information Products" -> new Color(255, 228, 230);
            case "Communications Services" -> new Color(207, 250, 254);
            default -> new Color(241, 245, 249);
        };
    }

    /** Keep labels readable with the Standard 14 PDF fonts. */
    private static String ascii(String value) {
        String normalized = value == null || value.isBlank() ? "n/a" : value.strip();
        String german = normalized
                .replace("Ä", "Ae")
                .replace("Ö", "Oe")
                .replace("Ü", "Ue")
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss")
                .replace('–', '-')
                .replace('—', '-')
                .replace('…', '.')
                .replace('·', '|');
        return Normalizer.normalize(german, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^\\x20-\\x7E]", "?");
    }
}
