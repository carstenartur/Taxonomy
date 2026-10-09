package com.taxonomy.plugins.mermaid;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;
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

class MermaidExportExtensionTest {

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

    @Test void serviceEntryContributesTheExistingFormatExactlyOnce() {
        var contributions = new MermaidPlugin().extensions();
        assertThat(contributions).singleElement().satisfies(extension -> {
            assertThat(extension).isInstanceOf(MermaidExportExtension.class);
            assertThat(extension.id()).isEqualTo(MermaidExportExtension.FORMAT_ID).isEqualTo("mermaid");
        });
    }
}
