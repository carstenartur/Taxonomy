package com.taxonomy.export.service;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class CanonicalDiagramExportServiceTest {

    private final CanonicalDiagramExportService service =
            new CanonicalDiagramExportService(
                    new VisioDiagramService(),
                    new VisioPackageBuilder(),
                    new ArchiMateDiagramService(),
                    new ArchiMateXmlExporter());

    @Test
    void serializesExactlyTheProvidedGraphThroughBothFormatAdapters()
            throws IOException {
        DiagramModel diagram = representativeDiagram();

        String archiMate = new String(
                service.exportAsArchiMate(diagram),
                StandardCharsets.UTF_8);
        String visioPage = readEntry(
                service.exportAsVisio(diagram),
                "visio/pages/page1.xml");

        assertThat(archiMate)
                .contains("id-capability")
                .contains("id-service")
                .contains("id-rel-supports")
                .contains("id-vc-supports");
        assertThat(visioPage)
                .contains("Secure communications")
                .contains("Messaging service")
                .contains("SUPPORTS");
    }

    @Test
    void refusesMissingCanonicalInputBeforeInvokingAnAdapter() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.exportAsArchiMate(null))
                .withMessage("canonicalDiagram");
        assertThatNullPointerException()
                .isThrownBy(() -> service.exportAsVisio(null))
                .withMessage("canonicalDiagram");
    }

    private static DiagramModel representativeDiagram() {
        return new DiagramModel(
                "Snapshot architecture",
                List.of(
                        new DiagramNode(
                                "capability",
                                "Secure communications",
                                "Capabilities",
                                0.91,
                                true,
                                1),
                        new DiagramNode(
                                "service",
                                "Messaging service",
                                "Core Services",
                                0.74,
                                false,
                                3)),
                List.of(new DiagramEdge(
                        "supports",
                        "capability",
                        "service",
                        "SUPPORTS",
                        0.74)),
                new DiagramLayout("LR", true));
    }

    private static String readEntry(byte[] archive, String entryName)
            throws IOException {
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return new String(
                            zip.readAllBytes(),
                            StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Missing VSDX entry " + entryName);
    }
}
