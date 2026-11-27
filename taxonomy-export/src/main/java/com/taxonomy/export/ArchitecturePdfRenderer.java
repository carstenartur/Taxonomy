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
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;

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
import java.util.function.Function;
import java.util.stream.Collectors;

/** Draws a vector PDF directly from the same deterministic scene returned to other export adapters. */
public final class ArchitecturePdfRenderer {

    private final ExportThemeProvider themes;

    public ArchitecturePdfRenderer() { this(ExportThemeProvider.defaults()); }

    public ArchitecturePdfRenderer(ExportThemeProvider themes) {
        this.themes = java.util.Objects.requireNonNull(themes, "themes");
    }

    private static final float PAGE_MARGIN = PdfDiagramPages.PAGE_MARGIN;
    private static final float HEADER_HEIGHT = PdfDiagramPages.HEADER_HEIGHT;
    private static final int MAX_PAGE_REFERENCES = 3;
    private static final float REFERENCE_ROW_HEIGHT = 32;

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
        var context = DiagramExportContext.snapshot(metadata, locale);
        var decoration = new Decoration(DiagramExportContext.title(scene.title(), locale), context.header(), List.of(language.text(
                "Generated from persisted architecture snapshot; no page screenshot and no LLM re-analysis.",
                "Aus dem gespeicherten Architektursnapshot erzeugt. Keine Bildschirmaufnahme und keine erneute KI-Analyse.")),
                context.warnings(), null);
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
        return renderCanonical(scene, provenance, evidence, Locale.ENGLISH);
    }

    public byte[] renderCanonical(DiagramScene scene, String provenance, DiagramCanonicalEvidence evidence, Locale locale) {
        var language = DiagramLanguage.from(locale);
        requireScene(scene);
        Objects.requireNonNull(evidence, "canonical evidence");
        var context = DiagramExportContext.canonical(provenance, evidence, locale);
        String title = DiagramExportContext.title(scene.title(), locale);
        return renderScene(scene, document -> configureSource(document, title, provenance),
                new Decoration(title, context.header(), List.of(language.text("Derived server layout · Canonical architecture", "Serverlayout · Kanonische Architektur")),
                        context.warnings(), evidence), Map.of(), Map.of(), language);
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
        return renderWorkingView(scene, nodeReasons, relationReasons, Locale.ENGLISH);
    }

    public byte[] renderWorkingView(DiagramScene scene, Map<String, String> nodeReasons,
                                    Map<String, Map<String, String>> relationReasons, Locale locale) {
        requireScene(scene);
        var language = DiagramLanguage.from(locale);
        String source = language.text("Client-provided working view", "Vom Client übermittelte Arbeitsansicht");
        return renderScene(scene, document -> configureSource(document, scene, source),
                new Decoration(language.text("Requirement architecture - working view", "Anforderungsarchitektur - Arbeitsansicht"),
                        List.of(language.text("Client-provided working view; source authority has not been verified.",
                                "Vom Client übermittelte Arbeitsansicht. Die Herkunft ist ungeprüft."), scene.title()),
                        List.of(language.text("Generated server layout | Client-provided working view | No re-analysis or persistence",
                                "Serverlayout | Vom Client übermittelte Arbeitsansicht | Keine erneute Analyse oder Speicherung")),
                        List.of(), null), nodeReasons, relationReasons, language);
    }

    /** A server-run assessment may be exported before it has been adopted as a project snapshot. */
    public byte[] renderAnalysis(DiagramScene scene, AnalysisDiagramSource source, Map<String, String> reasons, Locale locale) {
        requireScene(scene);
        var language = DiagramLanguage.from(locale);
        String origin = language.text("Server-side requirement analysis", "Serverseitige Anforderungsanalyse");
        var header = new ArrayList<String>();
        header.add(language.text("Analysis status: ", "Analysestatus: ") + safe(source.status()));
        if (source.provider() != null) header.add(language.text("Provider: ", "Anbieter: ") + source.provider());
        if (source.branch() != null) header.add("Branch: " + source.branch() + " · Commit: " + abbreviated(source.commit()));
        return renderScene(scene, document -> configureSource(document, scene, origin),
                new Decoration(scene.title(), header, List.of(origin), List.of(), null), reasons, Map.of(), language);
    }

    private static void requireScene(DiagramScene scene) {
        if (scene == null || scene.isEmpty()) throw new IllegalArgumentException("Architecture scene must not be empty");
    }

    private static void configureSource(PDDocument document, DiagramScene scene, String provenance) {
        configureSource(document, scene.title(), provenance);
    }

    private static void configureSource(PDDocument document, String title, String provenance) {
        var information = new PDDocumentInformation();
        information.setTitle(title);
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
            var diagramPages = plan.panels().stream().map(panel -> new PDPage(new PDRectangle(width, height))).toList();
            diagramPages.forEach(document::addPage);
            PDPage relationInventory = drawInventory(document, scene, plan.nodePages(), nodeReasons == null ? Map.of() : nodeReasons,
                    relationReasons == null ? Map.of() : relationReasons, theme, language, fonts, decoration);
            var nodes = scene.nodes().stream().collect(Collectors.toMap(DiagramSceneNode::id, Function.identity()));
            for (int index = 0; index < plan.panels().size(); index++) {
                var panel = plan.panels().get(index);
                DiagramScene part = panel.scene();
                PDPage page = diagramPages.get(index);
                int referenceCount = panel.crossPageRelationships().size();
                float referenceHeight = referenceCount == 0 ? 0
                        : 42 + REFERENCE_ROW_HEIGHT * Math.min(referenceCount, MAX_PAGE_REFERENCES);
                float scale = (float) Math.min(1.0, Math.min(
                        (width - 2 * PAGE_MARGIN) / Math.max(1.0, part.width()),
                        (height - 2 * PAGE_MARGIN - HEADER_HEIGHT - referenceHeight) / Math.max(1.0, part.height())));
                if (scale < 0.8f) throw new IllegalArgumentException("PDF panel is not readable");
                float diagramTop = height - PAGE_MARGIN - HEADER_HEIGHT;
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    background(stream, theme, width, height);
                    drawHeader(stream, decoration, width, height, theme, fonts);
                    drawEdges(stream, part, PAGE_MARGIN, diagramTop, scale, theme, language, fonts, decoration.canonical());
                    drawNodes(stream, part, PAGE_MARGIN, diagramTop, scale, theme, language, fonts, decoration.canonical() != null);
                    drawPageReferences(stream, page, panel, plan.nodePages(), diagramPages, relationInventory,
                            nodes, diagramTop - (float) part.height() * scale, width, theme, fonts, language, decoration.canonical());
                    drawFooter(stream, decoration, width, theme, language, fonts.regular());
                    if (plan.panels().size() > 1) {
                        text(stream, theme, fonts.regular(), 8, PAGE_MARGIN, 30,
                                language.text("Diagram D", "Diagramm D") + (index + 1) + "/" + plan.panels().size()
                                        + language.text(" | Cross-page relationships: ", " | Seitenübergreifende Beziehungen: ") + panel.crossPageRelationships().size()
                                        + language.text(" | Complete endpoints and diagram references: see export inventory.", " | Endpunkte und Diagrammverweise siehe Exportinventar."));
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to render architecture PDF", error);
        }
    }

    private static void drawPageReferences(PDPageContentStream stream, PDPage page, PdfDiagramPages.Panel panel,
            Map<String, Integer> nodePages, List<PDPage> pages, PDPage relationInventory,
            Map<String, DiagramSceneNode> nodes, float diagramBottom, float width, ExportTheme theme, Fonts fonts,
            DiagramLanguage language, DiagramCanonicalEvidence canonical) throws IOException {
        if (panel.crossPageRelationships().isEmpty()) return;
        float y = diagramBottom - 13;
        text(stream, theme, fonts.bold(), 12, PAGE_MARGIN, y,
                language.text("Connections to other pages", "Verbindungen zu anderen Seiten"));
        String inventoryLabel = language.text("Open relationship inventory", "Beziehungsinventar öffnen");
        int overflow = panel.crossPageRelationships().size() - MAX_PAGE_REFERENCES;
        if (overflow > 0) inventoryLabel += " (+" + overflow + ")";
        float inventoryLabelWidth = PdfDiagramText.width(fonts.bold(), 10, inventoryLabel);
        float inventoryX = width - PAGE_MARGIN - inventoryLabelWidth;
        drawLinkText(stream, page, relationInventory, List.of(inventoryLabel), theme, fonts.bold(), 10, inventoryX, y);

        float center = width / 2;
        float arrowHalfWidth = 90;
        float endpointWidth = center - arrowHalfWidth - PAGE_MARGIN - 14;
        for (var edge : panel.crossPageRelationships().stream().limit(MAX_PAGE_REFERENCES).toList()) {
            y -= REFERENCE_ROW_HEIGHT;
            int source = nodePages.get(edge.sourceId());
            int target = nodePages.get(edge.targetId());
            drawEndpointReference(stream, page, pages.get(source - 1), source, nodes.get(edge.sourceId()), theme,
                    fonts, PAGE_MARGIN, y, endpointWidth);
            drawEndpointReference(stream, page, pages.get(target - 1), target, nodes.get(edge.targetId()), theme,
                    fonts, center + arrowHalfWidth + 14, y, endpointWidth);
            String status = canonical == null ? DiagramPresentation.relationshipStatus(edge.metadata(), language.german())
                    : DiagramPresentation.canonicalRelationshipStatus(canonical, edge.id(), language);
            String type = PdfDiagramText.wrap(edge.relationType() + " · " + status,
                    fonts.bold(), 10, 2 * arrowHalfWidth, 1).getFirst();
            float typeWidth = PdfDiagramText.width(fonts.bold(), 10, type);
            text(stream, theme, fonts.bold(), 10, center - typeWidth / 2, y + 4, type);
            boolean proposed = canonical == null ? edge.metadata() != null && edge.metadata().proposed()
                    : DiagramPresentation.canonicalRelationshipProposed(canonical, edge.id());
            stream.setStrokingColor(Color.decode(theme.relationshipStroke(edge.relationType(), proposed)));
            stream.setLineDashPattern(proposed ? new float[]{5, 3} : new float[]{}, 0);
            stream.setLineWidth(1);
            stream.moveTo(center - arrowHalfWidth, y - 4);
            stream.lineTo(center + arrowHalfWidth, y - 4);
            stream.stroke();
            stream.setLineDashPattern(new float[]{}, 0);
            drawArrow(stream, center - arrowHalfWidth, y - 4, center + arrowHalfWidth, y - 4, 6);
        }
    }

    private static void drawEndpointReference(PDPageContentStream stream, PDPage page, PDPage destination, int panel,
            DiagramSceneNode node, ExportTheme theme, Fonts fonts, float x, float y, float maxWidth) throws IOException {
        var labels = PdfDiagramText.wrap(node.label(), fonts.bold(), 10, maxWidth, 2);
        drawLinkText(stream, page, destination, labels, theme, fonts.bold(), 10, x, y);
        String identity = PdfDiagramText.wrap("D" + panel + " · " + node.id(), fonts.regular(), 8, maxWidth, 1).getFirst();
        text(stream, theme, fonts.regular(), 8, x, y - labels.size() * 11, identity);
    }

    private static void drawLinkText(PDPageContentStream stream, PDPage page, PDPage destination, List<String> lines,
            ExportTheme theme, PDFont font, float size, float x, float y) throws IOException {
        boolean linked = page != destination;
        float width = 0;
        for (int index = 0; index < lines.size(); index++) {
            String value = lines.get(index);
            float baseline = y - index * 11;
            text(stream, theme, font, size, x, baseline, value);
            if (linked) {
                float lineWidth = PdfDiagramText.width(font, size, PdfDiagramText.printable(value, font));
                width = Math.max(width, lineWidth);
                stream.setStrokingColor(Color.decode(theme.canvasText("#394150")));
                stream.setLineWidth(.5f);
                stream.moveTo(x, baseline - 2);
                stream.lineTo(x + lineWidth, baseline - 2);
                stream.stroke();
            }
        }
        if (!linked) return;
        var target = new PDPageFitDestination();
        target.setPage(destination);
        var action = new PDActionGoTo();
        action.setDestination(target);
        var link = new PDAnnotationLink();
        var border = new PDBorderStyleDictionary();
        border.setWidth(0);
        link.setBorderStyle(border);
        float extraHeight = (lines.size() - 1) * 11;
        link.setRectangle(new PDRectangle(x, y - 3 - extraHeight, width, size + 4 + extraHeight));
        link.setAction(action);
        page.getAnnotations().add(link);
    }

    private static void background(PDPageContentStream stream, ExportTheme theme, float width, float height)
            throws IOException {
        stream.setNonStrokingColor(Color.decode(theme.canvasBackground()));
        stream.addRect(0, 0, width, height);
        stream.fill();
    }

    private static void configureMetadata(PDDocument document, DiagramScene scene, SnapshotMetadata metadata, DiagramLanguage language) {
        PDDocumentInformation information = new PDDocumentInformation();
        information.setTitle(DiagramExportContext.title(scene.title(), Locale.forLanguageTag(language.tag())));
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
        var routing = new DiagramEdgeRouting(scene);
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
            var detour = routing.detourForLine(edge);
            stream.moveTo(x1, y1);
            float arrowStartX = x1, arrowStartY = y1;
            if (detour == null) {
                stream.lineTo(x2, y2);
            } else {
                var points = detour.points();
                for (int i = 1; i < points.size(); i++) {
                    var point = points.get(i);
                    stream.lineTo(diagramX + (float) point.x() * scale, diagramTop - (float) point.y() * scale);
                }
                var beforeTarget = points.get(points.size() - 2);
                arrowStartX = diagramX + (float) beforeTarget.x() * scale;
                arrowStartY = diagramTop - (float) beforeTarget.y() * scale;
            }
            stream.stroke();
            drawArrow(stream, arrowStartX, arrowStartY, x2, y2, Math.max(4.0f, 7.0f * scale));
            if (edge.relationType() == null || edge.relationType().isBlank()) continue;
            String state = canonical == null ? DiagramPresentation.relationshipStatus(edge.metadata(), language.german())
                    : DiagramPresentation.canonicalRelationshipStatus(canonical, edge.id(), language);
            var anchors = DiagramEdgeRouting.captionAnchors(edge, detour).stream().map(anchor ->
                    new DiagramEdgeRouting.Point(diagramX + anchor.x() * scale, diagramTop - anchor.y() * scale)).toList();
            var caption = placeCaption(edge.relationType() + " · " + state, anchors,
                    diagramTop, nodeBounds, captions, fonts.regular(), labelSize);
            if (caption == null) caption = placeCaption(edge.relationType(), anchors,
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

    private static EdgeCaption placeCaption(String label, List<DiagramEdgeRouting.Point> anchors, float diagramTop,
            List<Rectangle2D.Float> nodeBounds, List<EdgeCaption> captions, PDFont font, float labelSize) throws IOException {
        String text = PdfDiagramText.wrap(label, font, labelSize, 210, 1).getFirst();
        float width = PdfDiagramText.width(font, labelSize, text) + 8;
        int[] offsets = {12, -20, 26, -34};
        for (var anchor : anchors) {
            for (int offset : offsets) {
                var rect = new Rectangle2D.Float((float) anchor.x() - width / 2, (float) anchor.y() + offset, width, 12);
                if (rect.x < PAGE_MARGIN || rect.getMaxX() > PdfDiagramPages.PAGE_WIDTH - PAGE_MARGIN
                        || rect.y < PAGE_MARGIN || rect.getMaxY() > diagramTop
                        || nodeBounds.stream().anyMatch(rect::intersects)
                        || captions.stream().anyMatch(other -> rect.intersects(other.bounds()))) continue;
                return new EdgeCaption(rect, text);
            }
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

            float inset = 8 * scale;
            float small = Math.max(7, 8 * scale);
            String score = canonical ? "" : language.score(node);
            float scoreWidth = PdfDiagramText.width(fonts.regular(), small, score);
            String id = PdfDiagramText.wrap(node.id(), fonts.bold(), small,
                    width - 3 * inset - scoreWidth, 1).getFirst();
            text(stream, theme.nodeText(node.type(), "#0f172a"), fonts.bold(), small, x + inset, top - 15 * scale, id);
            text(stream, theme.nodeText(node.type(), "#0f172a"), fonts.regular(), small, x + width - inset - scoreWidth, top - 15 * scale, score);
            var assignment = node.responsibility();
            int lines = assignment == null ? 2 : 1;
            if (canonical && node.height() >= LayeredDiagramLayoutService.NODE_HEIGHT) lines++;
            List<String> labelLines = PdfDiagramText.wrap(node.label(), fonts.regular(), 10 * scale,
                    width - 2 * inset, lines);
            float labelTop = canonical ? 27 : 34;
            for (int index = 0; index < labelLines.size(); index++) {
                text(stream, theme.nodeText(node.type(), "#0f172a"), fonts.regular(), 10 * scale, x + inset,
                        top - (labelTop + index * 13) * scale, labelLines.get(index));
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
            float typeBaseline = canonical && labelLines.size() == 3 && !node.container() ? 16 : 21;
            text(stream, theme.nodeText(node.type(), "#0f172a"), fonts.regular(), small, x + inset, y + typeBaseline * scale, type);
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
    private static PDPage drawInventory(PDDocument document, DiagramScene scene, Map<String, Integer> nodePages,
                                      Map<String, String> nodeReasons, Map<String, Map<String, String>> relationReasons,
                                      ExportTheme theme, DiagramLanguage language, Fonts fonts, Decoration decoration) throws IOException {
        var canonical = decoration.canonical();
        var entries = new ArrayList<Entry>();
        entries.add(new Entry(language.text("Export summary", "Exportübersicht"),
                DiagramPresentation.inventorySummary(scene, language, canonical)));
        if (!decoration.header().isEmpty()) entries.add(new Entry(language.text("Source context", "Quellenkontext"), decoration.header()));
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
                lines.addAll(DiagramPresentation.canonicalRelationshipDetails(canonical, edge.id(), language));
            }
            entries.add(new Entry(edge.id(), lines));
        }
        if (scene.edges().isEmpty()) entries.add(new Entry(language.text("Relationship inventory", "Beziehungsinventar"),
                List.of(language.text("Relationship inventory | No relationships in this export.", "Beziehungsinventar | Keine Beziehungen in diesem Export."))));
        float width = PDRectangle.A4.getWidth() - 2 * PAGE_MARGIN;
        int capacity = (int) ((PDRectangle.A4.getHeight() - 2 * PAGE_MARGIN - 36) / 13);
        var pages = inventoryPages(entries, fonts.regular(), width, capacity, language);
        PDPage relationshipInventory = null;
        for (int index = 0; index < pages.size(); index++) {
            var page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                background(stream, theme, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                float top = PDRectangle.A4.getHeight() - PAGE_MARGIN;
                text(stream, theme, fonts.bold(), 13, PAGE_MARGIN, top - 13,
                        language.text("Export inventory - ", "Exportinventar - ") + (index + 1));
                var lines = pages.get(index);
                if (lines.contains(language.text("Relationship inventory", "Beziehungsinventar")))
                    relationshipInventory = page;
                for (int row = 0; row < lines.size(); row++)
                    text(stream, theme, fonts.regular(), 9, PAGE_MARGIN, top - 36 - row * 13, lines.get(row));
            }
        }
        return relationshipInventory;
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
