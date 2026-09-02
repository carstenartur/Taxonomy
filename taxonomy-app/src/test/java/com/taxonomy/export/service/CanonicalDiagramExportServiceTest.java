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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
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

    @Test
    void rejectsDuplicateIdentityAndDanglingRelationsBeforeSerialization() {
        DiagramModel duplicateNode = new DiagramModel(
                "Duplicate node",
                List.of(
                        node("duplicate", "First", null, 0.8),
                        node("duplicate", "Second", null, 0.7)),
                List.of(),
                new DiagramLayout("LR", true));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.exportAsArchiMate(duplicateNode))
                .withMessageContaining(
                        "Duplicate canonical node identifier duplicate");

        DiagramModel danglingRelation = new DiagramModel(
                "Dangling relation",
                List.of(node("source", "Source", null, 0.8)),
                List.of(new DiagramEdge(
                        "dangling",
                        "source",
                        "missing",
                        "SUPPORTS",
                        0.7)),
                new DiagramLayout("LR", true));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.exportAsVisio(danglingRelation))
                .withMessageContaining(
                        "Canonical relation dangling target does not resolve: missing");
    }

    @Test
    void rejectsInvalidHierarchyAndNonFiniteRelevance() {
        DiagramModel unresolvedParent = new DiagramModel(
                "Unresolved parent",
                List.of(node("child", "Child", "missing", 0.8)),
                List.of(),
                new DiagramLayout("LR", true));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.exportAsVisio(unresolvedParent))
                .withMessageContaining(
                        "Canonical node child parent does not resolve: missing");

        DiagramModel nonFinite = new DiagramModel(
                "Non-finite relevance",
                List.of(node("source", "Source", null, Double.NaN)),
                List.of(),
                new DiagramLayout("LR", true));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.exportAsArchiMate(nonFinite))
                .withMessageContaining(
                        "must be a finite value between 0.0 and 1.0");
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

    private static DiagramNode node(
            String id,
            String label,
            String parentId,
            double relevance) {
        return new DiagramNode(
                id,
                label,
                "Capabilities",
                relevance,
                false,
                1,
                1,
                true,
                parentId,
                false);
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
