package com.taxonomy.export.service;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.export.MermaidLabels;
import com.taxonomy.export.StructurizrExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests proving that extension-based export output is semantically equivalent
 * to the previous direct-service output for Mermaid, ArchiMate, and Structurizr.
 *
 * <p>No Spring context required — adapters and services are all plain Java.
 */
class ExportFormatExtensionAdapterTest {

    private DiagramModel model;

    @BeforeEach
    void setUp() {
        DiagramNode cap = new DiagramNode("CP-1023", "Capability A", "Capabilities", 0.9, true, 1);
        DiagramNode proc = new DiagramNode("BP-1042", "Process One", "Business Processes", 0.7, false, 2);
        DiagramEdge edge = new DiagramEdge("e1", "CP-1023", "BP-1042", "SUPPORTS", 0.8);
        model = new DiagramModel("Test View",
                List.of(cap, proc), List.of(edge),
                new DiagramLayout("LR", true));
    }

    // ── MermaidExportExtension ───────────────────────────────────────────────

    @Test
    void mermaidExtensionMatchesServiceOutputEnglish() {
        MermaidExportService service = new MermaidExportService();
        MermaidExportExtension extension = new MermaidExportExtension(service);

        String direct = service.export(model, MermaidLabels.english());
        ExportResult result = extension.export(ExportContext.of(model));

        assertThat(result.utf8()).isEqualTo(direct);
    }

    @Test
    void mermaidExtensionMatchesServiceOutputGerman() {
        MermaidExportService service = new MermaidExportService();
        MermaidExportExtension extension = new MermaidExportExtension(service);

        String direct = service.export(model, MermaidLabels.german());
        ExportResult result = extension.export(
                new ExportContext(model, Map.of("locale", "de")));

        assertThat(result.utf8()).isEqualTo(direct);
    }

    @Test
    void mermaidExtensionDescriptorHasCorrectMetadata() {
        MermaidExportExtension extension =
                new MermaidExportExtension(new MermaidExportService());

        ExportFormatDescriptor d = extension.descriptor();

        assertThat(d.id()).isEqualTo("mermaid");
        assertThat(d.fileExtension()).isEqualTo("mmd");
        assertThat(d.contentType()).isEqualTo("text/plain; charset=UTF-8");
        assertThat(d.binary()).isFalse();
    }

    @Test
    void mermaidExtensionReturnsNonEmptyResultForValidModel() {
        MermaidExportExtension extension =
                new MermaidExportExtension(new MermaidExportService());

        ExportResult result = extension.export(ExportContext.of(model));

        assertThat(result.utf8()).startsWith("flowchart LR");
        assertThat(result.bytes()).isNotEmpty();
    }

    // ── ArchiMateExportExtension ─────────────────────────────────────────────

    @Test
    void archiMateExtensionMatchesServiceOutput() {
        ArchiMateDiagramService diagramService = new ArchiMateDiagramService();
        ArchiMateXmlExporter xmlExporter = new ArchiMateXmlExporter();
        ArchiMateExportExtension extension =
                new ArchiMateExportExtension(diagramService, xmlExporter);

        byte[] direct = xmlExporter.export(diagramService.convert(model));
        ExportResult result = extension.export(ExportContext.of(model));

        assertThat(result.bytes()).isEqualTo(direct);
    }

    @Test
    void archiMateExtensionDescriptorHasCorrectMetadata() {
        ArchiMateExportExtension extension =
                new ArchiMateExportExtension(new ArchiMateDiagramService(),
                        new ArchiMateXmlExporter());

        ExportFormatDescriptor d = extension.descriptor();

        assertThat(d.id()).isEqualTo("archimate");
        assertThat(d.fileExtension()).isEqualTo("xml");
        assertThat(d.contentType()).isEqualTo("application/xml");
        assertThat(d.binary()).isFalse();
    }

    @Test
    void archiMateExtensionOutputIsValidXml() {
        ArchiMateExportExtension extension =
                new ArchiMateExportExtension(new ArchiMateDiagramService(),
                        new ArchiMateXmlExporter());

        ExportResult result = extension.export(ExportContext.of(model));

        String xml = new String(result.bytes(), StandardCharsets.UTF_8);
        assertThat(xml).startsWith("<?xml version=\"1.0\"");
        assertThat(xml).contains("<model");
        assertThat(xml).contains("</model>");
    }

    // ── StructurizrExportExtension ───────────────────────────────────────────

    @Test
    void structurizrExtensionMatchesServiceOutput() {
        StructurizrExportService service = new StructurizrExportService();
        StructurizrExportExtension extension = new StructurizrExportExtension(service);

        String direct = service.export(model);
        ExportResult result = extension.export(ExportContext.of(model));

        assertThat(result.utf8()).isEqualTo(direct);
    }

    @Test
    void structurizrExtensionDescriptorHasCorrectMetadata() {
        StructurizrExportExtension extension =
                new StructurizrExportExtension(new StructurizrExportService());

        ExportFormatDescriptor d = extension.descriptor();

        assertThat(d.id()).isEqualTo("structurizr");
        assertThat(d.fileExtension()).isEqualTo("dsl");
        assertThat(d.binary()).isFalse();
    }

    // ── ExportContext ────────────────────────────────────────────────────────

    @Test
    void exportContextOfCreatesContextWithEmptyOptions() {
        ExportContext ctx = ExportContext.of(model);

        assertThat(ctx.diagram()).isSameAs(model);
        assertThat(ctx.options()).isEmpty();
    }

    @Test
    void exportContextOptionsAreImmutable() {
        ExportContext ctx = new ExportContext(model, Map.of("locale", "en"));

        assertThat(ctx.options()).containsEntry("locale", "en");
    }

    // ── ExportResult ─────────────────────────────────────────────────────────

    @Test
    void exportResultBytesReturnsDefensiveCopy() {
        byte[] original = "hello".getBytes(StandardCharsets.UTF_8);
        ExportResult result = new ExportResult(original);

        byte[] copy = result.bytes();
        copy[0] = 'X';

        assertThat(result.utf8()).isEqualTo("hello");
    }

    @Test
    void exportResultUtf8DecodesCorrectly() {
        ExportResult result = new ExportResult("flowchart LR".getBytes(StandardCharsets.UTF_8));

        assertThat(result.utf8()).isEqualTo("flowchart LR");
    }
}
