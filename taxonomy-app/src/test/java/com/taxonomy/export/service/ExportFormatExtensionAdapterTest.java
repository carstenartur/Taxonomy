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
import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.export.spi.ExportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExportFormatExtensionAdapterTest {

    private DiagramModel model;

    @BeforeEach
    void setUp() {
        DiagramNode capability = new DiagramNode(
                "CP-1023", "Capability A", "Capabilities", 0.9, true, 1);
        DiagramNode process = new DiagramNode(
                "BP-1042", "Process One", "Business Processes", 0.7, false, 2);
        DiagramEdge edge = new DiagramEdge(
                "e1", "CP-1023", "BP-1042", "SUPPORTS", 0.8);
        model = new DiagramModel(
                "Test View",
                List.of(capability, process),
                List.of(edge),
                new DiagramLayout("LR", true));
    }

    @Test
    void mermaidExtensionMatchesServiceOutputEnglish() {
        MermaidExportService service = new MermaidExportService();
        MermaidExportExtension extension = new MermaidExportExtension(service);
        ExportResult result = extension.export(ExportContext.of(model));
        assertThat(result.utf8()).isEqualTo(service.export(model, MermaidLabels.english()));
    }

    @Test
    void mermaidExtensionMatchesServiceOutputGerman() {
        MermaidExportService service = new MermaidExportService();
        MermaidExportExtension extension = new MermaidExportExtension(service);
        ExportResult result = extension.export(new ExportContext(model, Map.of("locale", "de")));
        assertThat(result.utf8()).isEqualTo(service.export(model, MermaidLabels.german()));
    }

    @Test
    void mermaidExtensionDescriptorHasCorrectMetadata() {
        ExportFormatDescriptor descriptor =
                new MermaidExportExtension(new MermaidExportService()).descriptor();
        assertThat(descriptor.id()).isEqualTo("mermaid");
        assertThat(descriptor.fileExtension()).isEqualTo("mmd");
        assertThat(descriptor.contentType()).isEqualTo("text/plain; charset=UTF-8");
        assertThat(descriptor.binary()).isFalse();
    }

    @Test
    void mermaidExtensionReturnsNonEmptyResultForValidModel() {
        ExportResult result = new MermaidExportExtension(new MermaidExportService())
                .export(ExportContext.of(model));
        assertThat(result.utf8()).startsWith("flowchart LR");
        assertThat(result.bytes()).isNotEmpty();
    }

    @Test
    void archiMateExtensionMatchesServiceOutput() {
        ArchiMateDiagramService diagramService = new ArchiMateDiagramService();
        ArchiMateXmlExporter xmlExporter = new ArchiMateXmlExporter();
        ArchiMateExportExtension extension =
                new ArchiMateExportExtension(diagramService, xmlExporter);
        assertThat(extension.export(ExportContext.of(model)).bytes())
                .isEqualTo(xmlExporter.export(diagramService.convert(model)));
    }

    @Test
    void archiMateExtensionDescriptorHasCorrectMetadata() {
        ExportFormatDescriptor descriptor = new ArchiMateExportExtension(
                new ArchiMateDiagramService(), new ArchiMateXmlExporter()).descriptor();
        assertThat(descriptor.id()).isEqualTo("archimate");
        assertThat(descriptor.fileExtension()).isEqualTo("xml");
        assertThat(descriptor.contentType()).isEqualTo("application/xml");
        assertThat(descriptor.binary()).isFalse();
    }

    @Test
    void archiMateExtensionOutputIsValidXml() {
        ExportResult result = new ArchiMateExportExtension(
                new ArchiMateDiagramService(), new ArchiMateXmlExporter())
                .export(ExportContext.of(model));
        String xml = new String(result.bytes(), StandardCharsets.UTF_8);
        assertThat(xml).startsWith("<?xml version=\"1.0\"");
        assertThat(xml).contains("<model");
        assertThat(xml).contains("</model>");
    }

    @Test
    void structurizrExtensionMatchesServiceOutput() {
        StructurizrExportService service = new StructurizrExportService();
        StructurizrExportExtension extension = new StructurizrExportExtension(service);
        assertThat(extension.export(ExportContext.of(model)).utf8())
                .isEqualTo(service.export(model));
    }

    @Test
    void structurizrExtensionDescriptorHasCorrectMetadata() {
        ExportFormatDescriptor descriptor =
                new StructurizrExportExtension(new StructurizrExportService()).descriptor();
        assertThat(descriptor.id()).isEqualTo("structurizr");
        assertThat(descriptor.fileExtension()).isEqualTo("dsl");
        assertThat(descriptor.binary()).isFalse();
    }

    @Test
    void exportContextOfCreatesContextWithEmptyOptions() {
        ExportContext context = ExportContext.of(model);
        assertThat(context.diagram()).isSameAs(model);
        assertThat(context.options()).isEmpty();
    }

    @Test
    void exportContextOptionsAreImmutable() {
        ExportContext context = new ExportContext(model, Map.of("locale", "en"));
        assertThat(context.options()).containsEntry("locale", "en");
    }

    @Test
    void exportResultBytesReturnsDefensiveCopy() {
        ExportResult result = new ExportResult("hello".getBytes(StandardCharsets.UTF_8));
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
