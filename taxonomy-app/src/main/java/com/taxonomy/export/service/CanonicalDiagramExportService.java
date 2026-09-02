package com.taxonomy.export.service;

import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.visio.VisioDocument;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Serializes an already selected canonical diagram without analysis, selection
 * or mutable preference lookups.
 */
@Service
public class CanonicalDiagramExportService {

    private final VisioDiagramService visioDiagramService;
    private final VisioPackageBuilder visioPackageBuilder;
    private final ArchiMateDiagramService archiMateDiagramService;
    private final ArchiMateXmlExporter archiMateXmlExporter;

    public CanonicalDiagramExportService(
            VisioDiagramService visioDiagramService,
            VisioPackageBuilder visioPackageBuilder,
            ArchiMateDiagramService archiMateDiagramService,
            ArchiMateXmlExporter archiMateXmlExporter) {
        this.visioDiagramService = visioDiagramService;
        this.visioPackageBuilder = visioPackageBuilder;
        this.archiMateDiagramService = archiMateDiagramService;
        this.archiMateXmlExporter = archiMateXmlExporter;
    }

    public byte[] exportAsArchiMate(DiagramModel canonicalDiagram) {
        DiagramModel diagram = requireCanonicalDiagram(canonicalDiagram);
        ArchiMateModel model = archiMateDiagramService.convert(diagram);
        return archiMateXmlExporter.export(model);
    }

    public byte[] exportAsVisio(DiagramModel canonicalDiagram) {
        DiagramModel diagram = requireCanonicalDiagram(canonicalDiagram);
        VisioDocument document = visioDiagramService.convert(diagram);
        try {
            return visioPackageBuilder.build(document);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Could not serialize the canonical diagram as VSDX",
                    exception);
        }
    }

    private static DiagramModel requireCanonicalDiagram(DiagramModel diagram) {
        Objects.requireNonNull(diagram, "canonicalDiagram");
        Objects.requireNonNull(diagram.nodes(), "canonicalDiagram.nodes");
        Objects.requireNonNull(diagram.edges(), "canonicalDiagram.edges");
        Objects.requireNonNull(diagram.layout(), "canonicalDiagram.layout");
        return diagram;
    }
}
