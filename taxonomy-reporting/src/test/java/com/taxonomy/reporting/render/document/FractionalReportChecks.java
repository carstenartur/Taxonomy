package com.taxonomy.reporting.render.document;

import com.taxonomy.diagram.*;
import com.taxonomy.dto.*;
import com.taxonomy.export.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

/** Exercises real exported documents rather than matching implementation source strings. */
public final class FractionalReportChecks {
    private static DiagramModel model(double relevance) {
        return new DiagramModel("Fractional working-time example", List.of(
                new DiagramNode("BP-1", "Record working time", "Business Processes", relevance, true, 1, 3, false, null, false)),
                List.of(), new DiagramLayout("LR", true));
    }
    public static void pdf(Path output) throws Exception {
        var bytes = new ArchitecturePdfRenderer().render(new LayeredDiagramLayoutService().layout(model(.00125)), "Synthetic decimal regression");
        if (output != null) Files.write(output, bytes);
        try (var document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            require(text.contains("0.125%"), "PDF rounded0.125% to zero: " + text);
        }
    }
    public static void mermaid() {
        var service = new MermaidExportService();
        require(service.export(model(.00125)).contains("0.125%"), "Mermaid rounded a fraction");
        require(service.exportShowcase(model(.00125)).contains("0.125%"), "Showcase rounded a fraction");
    }
    public static void svg() throws Exception {
        var service = new SvgDiagramRenderer();
        String normal = service.render(new LayeredDiagramLayoutService().layout(model(.00125)));
        require(normal.contains("0.125%"), "SVG rounded a fraction");
        String tiny = service.render(new LayeredDiagramLayoutService().layout(model(1e-12)));
        require(tiny.contains("&lt;0.000001%"), "tiny score must stay visibly positive and XML-escaped");
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.newDocumentBuilder().parse(new ByteArrayInputStream(tiny.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    public static void word(Path output) throws Exception {
        var report = new ArchitectureReport(); report.setBusinessText("Record working time");
        var element = new RequirementElementView(); element.setNodeCode("BP-1"); element.setTitle("Record working time");
        element.setTaxonomySheet("Business Processes"); element.setRelevance(.00125); element.setAnchor(true); element.setTaxonomyDepth(3);
        var view = new RequirementArchitectureView(); view.setIncludedElements(List.of(element)); report.setArchitectureView(view);
        var bytes = new ArchitectureReportDocxRenderer().render(report);
        if (output != null) Files.write(output, bytes);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes)); var text = new XWPFWordExtractor(doc)) {
            require(text.getText().contains("0.125%"), "Word element table rounded a fraction: " + text.getText());
        }
    }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path output = args.length > 1 ? Path.of(args[1]) : null;
        switch (args[0]) { case "pdf" -> pdf(output); case "mermaid" -> mermaid(); case "svg" -> svg(); case "word" -> word(output);
            default -> throw new IllegalArgumentException(args[0]); }
        System.out.println("PASS export " + args[0]);
    }
}
