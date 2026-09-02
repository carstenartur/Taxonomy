package com.taxonomy.export.service;

import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.visio.VisioDocument;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

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
        requireNonBlank(
                diagram.layout().direction(),
                "canonicalDiagram.layout.direction");

        Set<String> nodeIds = new HashSet<>();
        for (int index = 0; index < diagram.nodes().size(); index++) {
            DiagramNode node = diagram.nodes().get(index);
            if (node == null) {
                throw invalid("Canonical node " + index + " must not be null");
            }
            String id = requireNonBlank(
                    node.id(), "Canonical node " + index + " identifier");
            if (!nodeIds.add(id)) {
                throw invalid("Duplicate canonical node identifier " + id);
            }
            requireNonBlank(node.label(), "Canonical node " + id + " label");
            requireNonBlank(node.type(), "Canonical node " + id + " type");
            requireUnitInterval(
                    node.relevance(), "Canonical node " + id + " relevance");
        }

        for (DiagramNode node : diagram.nodes()) {
            String parentId = node.parentId();
            if (parentId == null) {
                continue;
            }
            parentId = requireNonBlank(
                    parentId, "Canonical node " + node.id() + " parent");
            if (!nodeIds.contains(parentId)) {
                throw invalid("Canonical node " + node.id()
                        + " parent does not resolve: " + parentId);
            }
            if (node.id().equals(parentId)) {
                throw invalid("Canonical node " + node.id()
                        + " must not be its own parent");
            }
        }

        Set<String> edgeIds = new HashSet<>();
        for (int index = 0; index < diagram.edges().size(); index++) {
            DiagramEdge edge = diagram.edges().get(index);
            if (edge == null) {
                throw invalid("Canonical relation " + index + " must not be null");
            }
            String id = requireNonBlank(
                    edge.id(), "Canonical relation " + index + " identifier");
            if (!edgeIds.add(id)) {
                throw invalid("Duplicate canonical relation identifier " + id);
            }
            String sourceId = requireNonBlank(
                    edge.sourceId(), "Canonical relation " + id + " source");
            String targetId = requireNonBlank(
                    edge.targetId(), "Canonical relation " + id + " target");
            if (!nodeIds.contains(sourceId)) {
                throw invalid("Canonical relation " + id
                        + " source does not resolve: " + sourceId);
            }
            if (!nodeIds.contains(targetId)) {
                throw invalid("Canonical relation " + id
                        + " target does not resolve: " + targetId);
            }
            requireNonBlank(
                    edge.relationType(),
                    "Canonical relation " + id + " type");
            requireUnitInterval(
                    edge.relevance(),
                    "Canonical relation " + id + " relevance");
        }
        return diagram;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        return value;
    }

    private static void requireUnitInterval(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw invalid(field
                    + " must be a finite value between 0.0 and 1.0, but was "
                    + value);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
