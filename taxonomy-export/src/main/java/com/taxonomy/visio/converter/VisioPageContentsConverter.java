package com.taxonomy.visio.converter;

import com.taxonomy.visio.VisioConnect;
import com.taxonomy.visio.VisioTextBox;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioShape;
import com.taxonomy.visio.VisioProperty;
import com.taxonomy.visio.VisioNativeShape;
import com.taxonomy.visio.VisioConnectionPoints;
import com.taxonomy.visio.VisioContainers;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Marshals native master instances with cached geometry and explicit point-glue
 * dependencies. Connection records and endpoint formulas describe the same ports.
 */
public class VisioPageContentsConverter implements Converter {

    private static final String VISIO_NS =
            "http://schemas.microsoft.com/office/visio/2012/main";
    private static final String REL_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String LINE_COLOR = "#44546A";
    private final VisioNativeShape masterDefinition;

    public VisioPageContentsConverter() { this(null); }

    /** Uses the same geometry policy to emit an original document master. */
    public VisioPageContentsConverter(VisioNativeShape masterDefinition) {
        this.masterDefinition = masterDefinition;
    }

    @Override
    public boolean canConvert(Class type) {
        return VisioPage.class.equals(type);
    }

    @Override
    public void marshal(
            Object source,
            HierarchicalStreamWriter writer,
            MarshallingContext context) {
        VisioPage page = (VisioPage) source;
        Map<String, VisioShape> shapesById = indexShapes(page);

        writer.addAttribute("xmlns", VISIO_NS);
        writer.addAttribute("xmlns:r", REL_NS);

        if (masterDefinition != null) {
            writer.startNode("Shapes");
            if (masterDefinition == VisioNativeShape.CONNECTOR) {
                writeConnectorShape(writer, 1, new VisioConnect("1", "2", ""),
                        new VisioShape("1", "", 0, 0, 0, 0, "", false),
                        new VisioShape("2", "", 2, 0, 0, 0, "", false));
            } else {
                double width = masterDefinition == VisioNativeShape.CONTAINER ? 4 : 2;
                double height = masterDefinition == VisioNativeShape.CONTAINER ? 3 : 1;
                writeRegularShape(writer, new VisioShape("1", masterDefinition.label(), width / 2, height / 2, width, height,
                        masterDefinition.label(), false), masterDefinition, null);
            }
            writer.endNode();
            return;
        }

        VisioContainers.Membership membership = VisioContainers.membership(page);

        if (!page.getShapes().isEmpty() || !page.getConnects().isEmpty() || page.getLegend() != null) {
            writer.startNode("Shapes");
            // Container outlines are behind both members and their connectors.
            for (VisioShape shape : page.getShapes()) if (VisioNativeShape.isContainer(shape))
                writeRegularShape(writer, shape, VisioNativeShape.CONTAINER, membership.formula(shape.getId()));
            long connectorId = maximumShapeId(page);
            for (VisioConnect connect : page.getConnects()) {
                connectorId++;
                writeConnectorShape(
                        writer,
                        connectorId,
                        connect,
                        requireShape(shapesById, connect.getFromShape(), "source"),
                        requireShape(shapesById, connect.getToShape(), "target"));
            }
            // Shape order is drawing order: opaque nodes cover crossing strokes.
            for (VisioShape shape : page.getShapes()) if (!VisioNativeShape.isContainer(shape))
                writeRegularShape(writer, shape, VisioNativeShape.of(shape), membership.formula(shape.getId()));
            if (page.getLegend() != null) {
                var box = page.getLegend();
                var legend = new VisioShape(Long.toString(connectorId + 1), box.text(), box.x(), box.y(), box.width(), box.height(), "Legend", false);
                legend.getProperties().put("taxonomy.presentationOnly", VisioProperty.bool(true));
                writeRegularShape(writer, legend, null, null);
            }
            writer.endNode();
        }

        if (!page.getConnects().isEmpty()) {
            writer.startNode("Connects");
            long connectorId = maximumShapeId(page);
            for (VisioConnect connect : page.getConnects()) {
                connectorId++;
                var endpoints = VisioConnectionPoints.between(shapesById.get(connect.getFromShape()), shapesById.get(connect.getToShape()));
                writeGlue(writer, connectorId, "BeginX", connect.getFromShape(), endpoints.source().side());
                writeGlue(writer, connectorId, "EndX", connect.getToShape(), endpoints.target().side());
            }
            writer.endNode();
        }
    }

    @Override
    public Object unmarshal(
            HierarchicalStreamReader reader,
            UnmarshallingContext context) {
        throw new UnsupportedOperationException(
                "VisioPageContentsConverter does not support unmarshaling");
    }

    private void writeRegularShape(
            HierarchicalStreamWriter writer,
            VisioShape shape, VisioNativeShape kind, String relationships) {
        boolean evidence = VisioNativeShape.isPresentationOnly(shape) && "Evidence".equals(shape.getType());
        writer.startNode("Shape");
        writer.addAttribute("ID", shape.getId());
        writer.addAttribute("NameU", "TaxonomyShape." + shape.getId());
        writer.addAttribute("IsCustomNameU", "1");
        writer.addAttribute("Name", evidence ? "Full recorded evidence" : shape.getText());
        writer.addAttribute("IsCustomName", "1");
        writer.addAttribute("Type", "Shape");
        if (kind != null && masterDefinition == null) writer.addAttribute("Master", Integer.toString(kind.id()));
        writeStyleReferences(writer);

        writeCell(writer, "PinX", decimal(shape.getX()));
        writeCell(writer, "PinY", decimal(shape.getY()));
        writeCell(writer, "Width", decimal(shape.getWidth()));
        writeCell(writer, "Height", decimal(shape.getHeight()));
        writeCell(writer, "LocPinX", decimal(shape.getWidth() / 2.0), "Width*0.5");
        writeCell(writer, "LocPinY", decimal(shape.getHeight() / 2.0), "Height*0.5");
        writeCell(writer, "Angle", "0");
        writeCell(writer, "FillForegnd", com.taxonomy.export.DiagramPresentation.fill(shape.getType()));
        boolean container = kind == VisioNativeShape.CONTAINER;
        writeCell(writer, "FillPattern", container ? "0" : "1");
        writeCell(writer, "LineColor", LINE_COLOR);
        writeCell(writer, "LinePattern", "1");
        writeCell(writer, "LineWeight", shape.isAnchor() ? "0.02777777777777778" : "0.01388888888888889");
        if (kind == VisioNativeShape.SERVICE) writeCell(writer, "Rounding", decimal(Math.min(shape.getWidth(), shape.getHeight()) * .15), "MIN(Width,Height)*0.15");
        if (kind == VisioNativeShape.APPLICATION) writeCell(writer, "RightMargin", decimal(shape.getWidth() * .2), "Width*0.2");
        writeCell(writer, "VerticalAlign", evidence || container ? "0" : "1");
        if (kind != null) {
            writeCell(writer, "ObjType", container ? "4" : "1");
            writeConnectionPoints(writer, shape.getWidth(), shape.getHeight());
        }
        if (relationships != null) writeCell(writer, "Relationships", "0", relationships);
        if (container) {
            writer.startNode("Section"); writer.addAttribute("N", "User");
            writer.startNode("Row"); writer.addAttribute("N", "msvStructureType");
            writeStringCell(writer, "Value", "Container");
            writer.endNode(); writer.endNode();
            double header = Math.min(shape.getHeight(), VisioContainers.HEADER_HEIGHT);
            writeCell(writer, "TxtPinX", decimal(shape.getWidth() / 2), "Width*0.5");
            writeCell(writer, "TxtPinY", decimal(shape.getHeight() - header / 2), "Height-TxtHeight*0.5");
            writeCell(writer, "TxtWidth", decimal(shape.getWidth()), "Width");
            writeCell(writer, "TxtHeight", decimal(header), "MIN(Height," + VisioContainers.HEADER_HEIGHT + ")");
            writeCell(writer, "TxtLocPinX", decimal(shape.getWidth() / 2), "TxtWidth*0.5");
            writeCell(writer, "TxtLocPinY", decimal(header / 2), "TxtHeight*0.5");
        }
        if (evidence) {
            writeCell(writer, "LeftMargin", "0.1");
            writeCell(writer, "RightMargin", "0.1");
            writeCell(writer, "TopMargin", "0.1");
            writeCell(writer, "BottomMargin", "0.1");
        }
        writeCharacterStyle(writer, evidence || container ? 10 : shape.getHeight() >= 1 ? 12 : 10);
        if (evidence) {
            writer.startNode("Section"); writer.addAttribute("N", "Paragraph");
            writer.startNode("Row"); writer.addAttribute("IX", "0");
            writeCell(writer, "HorzAlign", "0");
            writeCell(writer, "SpLine", "-1.2");
            writer.endNode(); writer.endNode();
        }
        writeNativeGeometry(writer, kind, shape.getWidth(), shape.getHeight(), kind != null && masterDefinition == null);
        writeProperties(writer, shape.getProperties());

        writer.startNode("Text");
        writer.setValue(shape.getText());
        writer.endNode();
        writer.endNode();
    }

    private void writeConnectorShape(
            HierarchicalStreamWriter writer,
            long connectorId,
            VisioConnect connect,
            VisioShape source,
            VisioShape target) {
        var endpoints = VisioConnectionPoints.between(source, target);
        double beginX = endpoints.source().x();
        double beginY = endpoints.source().y();
        double endX = endpoints.target().x();
        double endY = endpoints.target().y();
        double width = Math.hypot(endX - beginX, endY - beginY);
        double angle = Math.atan2(endY - beginY, endX - beginX);

        writer.startNode("Shape");
        writer.addAttribute("ID", String.valueOf(connectorId));
        writer.addAttribute("NameU", "TaxonomyConnector." + connectorId);
        writer.addAttribute("IsCustomNameU", "1");
        writer.addAttribute("Type", "Shape");
        if (masterDefinition == null) writer.addAttribute("Master", Integer.toString(VisioNativeShape.CONNECTOR.id()));
        writeStyleReferences(writer);

        writeCell(writer, "OneD", "1");
        // Routable native connector. Current attachments are explicit static
        // point glue; GlueType permits a person to reattach dynamically in Visio.
        writeCell(writer, "ObjType", "2");
        writeCell(writer, "GlueType", "2");

        // Persist meaningful calculated values as well as formulas. Consumers
        // that do not immediately recalculate the ShapeSheet still render the
        // connector between the selected source and target positions.
        String fromFormula = masterDefinition == null ? endpoints.source().formula(source.getId()) : null;
        String toFormula = masterDefinition == null ? endpoints.target().formula(target.getId()) : null;
        writeCell(writer, "BeginX", decimal(beginX), fromFormula);
        writeCell(writer, "BeginY", decimal(beginY), fromFormula);
        writeCell(writer, "EndX", decimal(endX), toFormula);
        writeCell(writer, "EndY", decimal(endY), toFormula);

        // Use Visio's normal rotating 1-D transform. The local geometry is a
        // horizontal line of Width; the endpoint-derived Pin/Width/Angle cells
        // position and rotate it when glue moves either endpoint.
        writeCell(writer, "PinX", decimal((beginX + endX) / 2.0),
                "(BeginX+EndX)*0.5");
        writeCell(writer, "PinY", decimal((beginY + endY) / 2.0),
                "(BeginY+EndY)*0.5");
        writeCell(writer, "Width", decimal(width),
                "SQRT((EndX-BeginX)^2+(EndY-BeginY)^2)");
        writeCell(writer, "Height", "0");
        writeCell(writer, "LocPinX", decimal(width / 2.0), "Width*0.5");
        writeCell(writer, "LocPinY", "0");
        writeCell(writer, "Angle", decimal(angle),
                "ATAN2(EndY-BeginY,EndX-BeginX)");
        boolean proposed = connect.getProperties().containsKey("taxonomy.proposed")
                && "true".equals(connect.getProperties().get("taxonomy.proposed").value());
        writeCell(writer, "LineColor", proposed ? "#9333EA" : LINE_COLOR);
        writeCell(writer, "LinePattern", proposed ? "2" : "1");
        writeCell(writer, "LineWeight", "0.01388888888888889");
        writeCell(writer, "EndArrow", "13");
        writeCell(writer, "EndArrowSize", "2");
        VisioTextBox box = connect.getTextBox() != null ? connect.getTextBox()
                : new VisioTextBox(connect.getRelationType(), (beginX + endX) / 2,
                        (beginY + endY) / 2, 2, 0.5);
        // Invert the rotating shape transform. Constant page-axis offsets from
        // the midpoint stay upright and attached when Visio recalculates glue.
        double dx = box.x() - (beginX + endX) / 2;
        double dy = box.y() - (beginY + endY) / 2;
        writeCell(writer, "TxtPinX", decimal(width / 2 + dx * Math.cos(angle) + dy * Math.sin(angle)),
                "Width*0.5+(" + decimal(dx) + ")*COS(Angle)+(" + decimal(dy) + ")*SIN(Angle)");
        writeCell(writer, "TxtPinY", decimal(-dx * Math.sin(angle) + dy * Math.cos(angle)),
                "-(" + decimal(dx) + ")*SIN(Angle)+(" + decimal(dy) + ")*COS(Angle)");
        writeCell(writer, "TxtWidth", decimal(box.width()));
        writeCell(writer, "TxtHeight", decimal(box.height()));
        writeCell(writer, "TxtLocPinX", decimal(box.width() / 2), "TxtWidth*0.5");
        writeCell(writer, "TxtLocPinY", decimal(box.height() / 2), "TxtHeight*0.5");
        writeCell(writer, "TxtAngle", decimal(-angle), "-Angle");
        writeCell(writer, "TextBkgnd", "#FFFFFF");
        writeCell(writer, "TextBkgndTrans", "0");
        writeCell(writer, "VerticalAlign", "1");
        writeCharacterStyle(writer, box.height() >= 1 ? 12 : 10);
        writeLineGeometry(writer, width);
        writeProperties(writer, connect.getProperties());

        writer.startNode("Text");
        writer.setValue(box.text());
        writer.endNode();
        writer.endNode();
    }

    private static void writeCharacterStyle(HierarchicalStreamWriter writer, int points) {
        writer.startNode("Section");
        writer.addAttribute("N", "Character");
        writer.startNode("Row");
        writer.addAttribute("IX", "0");
        writeCell(writer, "Size", decimal(points / 72.0)); // Fixed readable size; never shrink to fit.
        writeCell(writer, "Color", "#172B4D");
        writer.endNode();
        writer.endNode();
    }

    private static void writeGlue(
            HierarchicalStreamWriter writer,
            long connectorId,
            String fromCell,
            String endpointShapeId, VisioConnectionPoints.Side side) {
        writer.startNode("Connect");
        writer.addAttribute("FromSheet", String.valueOf(connectorId));
        writer.addAttribute("FromCell", fromCell);
        writer.addAttribute("FromPart", fromCell.equals("BeginX") ? "9" : "12");
        writer.addAttribute("ToSheet", endpointShapeId);
        writer.addAttribute("ToCell", "Connections.X" + side.number());
        writer.addAttribute("ToPart", Integer.toString(99 + side.number()));
        writer.endNode();
    }

    private static void writeStyleReferences(HierarchicalStreamWriter writer) {
        writer.addAttribute("LineStyle", "0");
        writer.addAttribute("FillStyle", "0");
        writer.addAttribute("TextStyle", "0");
    }

    private static void writeProperties(HierarchicalStreamWriter writer, Map<String, VisioProperty> properties) {
        if (properties.isEmpty()) return;
        writer.startNode("Section");
        writer.addAttribute("N", "Property");
        for (var entry : new java.util.TreeMap<>(properties).entrySet()) {
            writer.startNode("Row");
            // Hex encoding is injective and uses only portable ShapeSheet row-name characters.
            writer.addAttribute("N", "t" + java.util.HexFormat.of().formatHex(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            writeCell(writer, "Label", entry.getKey());
            writeCell(writer, "Type", entry.getValue().shapeType());
            writer.startNode("Cell");
            writer.addAttribute("N", "Value");
            writer.addAttribute("V", entry.getValue().shapeValue());
            if (entry.getValue().kind() == VisioProperty.Kind.STRING) writer.addAttribute("U", "STR");
            writer.endNode();
            writer.endNode();
        }
        writer.endNode();
        var evidencePage = properties.get("taxonomy.evidencePage");
        var requirementUrl = properties.get("taxonomy.requirementUrl");
        if (evidencePage != null || requirementUrl != null) {
            writer.startNode("Section"); writer.addAttribute("N", "Hyperlink");
            if (evidencePage != null) {
                writer.startNode("Row"); writer.addAttribute("N", "FullEvidence");
                writeStringCell(writer, "Description", "Full recorded evidence");
                writeStringCell(writer, "Address", "");
                writeStringCell(writer, "SubAddress", evidencePage.value());
                writeCell(writer, "Default", "1");
                writer.endNode();
            }
            if (requirementUrl != null) {
                com.taxonomy.visio.VisioHyperlinks.validateRequirementUrl(requirementUrl);
                writer.startNode("Row"); writer.addAttribute("N", "Requirement");
                writeStringCell(writer, "Description", "Open requirement in Taxonomy");
                writeStringCell(writer, "Address", requirementUrl.value());
                writeCell(writer, "Default", evidencePage == null ? "1" : "0");
                writer.endNode();
            }
            writer.endNode();
        }
    }

    private static void writeConnectionPoints(HierarchicalStreamWriter writer, double width, double height) {
        writer.startNode("Section"); writer.addAttribute("N", "Connection");
        for (var side : VisioConnectionPoints.Side.values()) {
            writer.startNode("Row"); writer.addAttribute("IX", Integer.toString(side.number() - 1));
            writeCell(writer, "X", decimal(width * side.xFraction()), "Width*" + decimal(side.xFraction()));
            writeCell(writer, "Y", decimal(height * side.yFraction()), "Height*" + decimal(side.yFraction()));
            writeCell(writer, "DirX", "0");
            writeCell(writer, "DirY", "0");
            writeCell(writer, "Type", "0");
            writer.endNode();
        }
        writer.endNode();
    }

    private static void writeNativeGeometry(HierarchicalStreamWriter writer, VisioNativeShape kind,
            double width, double height, boolean inherited) {
        if (kind == null) {
            writeRectangleGeometry(writer, width, height);
            return;
        }
        double[][] outline = switch (kind) {
            case INFORMATION -> new double[][]{{0, 0}, {1, 0}, {1, .75}, {.88, 1}, {0, 1}, {0, 0}};
            case ROLE -> new double[][]{{.08, 0}, {.92, 0}, {1, .5}, {.92, 1}, {.08, 1}, {0, .5}, {.08, 0}};
            default -> new double[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}, {0, 0}};
        };
        writePolygon(writer, 0, width, height, outline, kind == VisioNativeShape.CONTAINER, inherited);
        if (kind == VisioNativeShape.INFORMATION)
            writePolygon(writer, 1, width, height, new double[][]{{.88, 1}, {.88, .75}, {1, .75}}, true, inherited);
        if (kind == VisioNativeShape.APPLICATION) {
            writePolygon(writer, 1, width, height, new double[][]{{.82, .72}, {.97, .72}, {.97, .95}, {.82, .95}, {.82, .72}}, true, inherited);
            writePolygon(writer, 2, width, height, new double[][]{{.78, .76}, {.86, .76}, {.86, .81}, {.78, .81}, {.78, .76}}, true, inherited);
            writePolygon(writer, 3, width, height, new double[][]{{.78, .86}, {.86, .86}, {.86, .91}, {.78, .91}, {.78, .86}}, true, inherited);
        }
        if (kind == VisioNativeShape.CONTAINER) {
            writer.startNode("Section"); writer.addAttribute("N", "Geometry"); writer.addAttribute("IX", "1");
            writeCell(writer, "NoFill", "1");
            double bottom = height - Math.min(height, VisioContainers.HEADER_HEIGHT);
            String y = inherited ? "Inh" : "Height-MIN(Height," + VisioContainers.HEADER_HEIGHT + ")";
            writeGeometryRow(writer, "MoveTo", 1, 0, bottom, inherited ? "Inh" : null, y);
            writeGeometryRow(writer, "LineTo", 2, width, bottom, inherited ? "Inh" : "Width", y);
            writer.endNode();
        }
    }

    private static void writePolygon(HierarchicalStreamWriter writer, int section, double width, double height,
            double[][] points, boolean noFill, boolean inherited) {
        writer.startNode("Section"); writer.addAttribute("N", "Geometry"); writer.addAttribute("IX", Integer.toString(section));
        if (noFill) writeCell(writer, "NoFill", "1");
        for (int index = 0; index < points.length; index++) {
            double x = points[index][0], y = points[index][1];
            writeGeometryRow(writer, index == 0 ? "MoveTo" : "LineTo", index + 1, width * x, height * y,
                    inherited ? "Inh" : "Width*" + decimal(x), inherited ? "Inh" : "Height*" + decimal(y));
        }
        writer.endNode();
    }

    private static void writeStringCell(HierarchicalStreamWriter writer, String name, String value) {
        writer.startNode("Cell"); writer.addAttribute("N", name); writer.addAttribute("V", value); writer.addAttribute("U", "STR"); writer.endNode();
    }

    private static void writeRectangleGeometry(
            HierarchicalStreamWriter writer,
            double width,
            double height) {
        writer.startNode("Section");
        writer.addAttribute("N", "Geometry");
        writer.addAttribute("IX", "0");
        writeGeometryRow(writer, "MoveTo", 1, 0, 0, null, null);
        writeGeometryRow(writer, "LineTo", 2, width, 0, "Width", null);
        writeGeometryRow(writer, "LineTo", 3, width, height, "Width", "Height");
        writeGeometryRow(writer, "LineTo", 4, 0, height, null, "Height");
        writeGeometryRow(writer, "LineTo", 5, 0, 0, null, null);
        writer.endNode();
    }

    private static void writeLineGeometry(
            HierarchicalStreamWriter writer,
            double width) {
        writer.startNode("Section");
        writer.addAttribute("N", "Geometry");
        writer.addAttribute("IX", "0");
        writeGeometryRow(writer, "MoveTo", 1, 0, 0, null, null);
        writeGeometryRow(writer, "LineTo", 2, width, 0, "Width", null);
        writer.endNode();
    }

    private static void writeGeometryRow(
            HierarchicalStreamWriter writer,
            String type,
            int index,
            double x,
            double y,
            String xFormula,
            String yFormula) {
        writer.startNode("Row");
        writer.addAttribute("T", type);
        writer.addAttribute("IX", String.valueOf(index));
        writeCell(writer, "X", decimal(x), xFormula);
        writeCell(writer, "Y", decimal(y), yFormula);
        writer.endNode();
    }

    private static void writeCell(
            HierarchicalStreamWriter writer,
            String name,
            String value) {
        writeCell(writer, name, value, null);
    }

    private static void writeCell(
            HierarchicalStreamWriter writer,
            String name,
            String value,
            String formula) {
        writer.startNode("Cell");
        writer.addAttribute("N", name);
        writer.addAttribute("V", value);
        if (formula != null) {
            writer.addAttribute("F", formula);
        }
        writer.endNode();
    }

    private static Map<String, VisioShape> indexShapes(VisioPage page) {
        Map<String, VisioShape> shapes = new LinkedHashMap<>();
        for (VisioShape shape : page.getShapes()) {
            shapes.put(shape.getId(), shape);
        }
        return shapes;
    }

    private static VisioShape requireShape(
            Map<String, VisioShape> shapes,
            String shapeId,
            String role) {
        VisioShape shape = shapes.get(shapeId);
        if (shape == null) {
            throw new IllegalArgumentException(
                    "Connector references missing " + role + " shape " + shapeId);
        }
        return shape;
    }

    private static long maximumShapeId(VisioPage page) {
        return page.getShapes().stream()
                .mapToLong(shape -> Long.parseLong(shape.getId()))
                .max()
                .orElse(0);
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
