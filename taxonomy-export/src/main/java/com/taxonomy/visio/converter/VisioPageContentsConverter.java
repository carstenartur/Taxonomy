package com.taxonomy.visio.converter;

import com.taxonomy.visio.VisioConnect;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioShape;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

import java.math.BigDecimal;

/**
 * Marshals one masterless page using the Visio 2012 {@code PageContents}
 * structure. Regular nodes carry explicit rectangle geometry; relationship
 * connectors carry explicit one-dimensional line geometry and are glued through
 * the page's {@code Connects} collection.
 */
public class VisioPageContentsConverter implements Converter {

    private static final String VISIO_NS =
            "http://schemas.microsoft.com/office/visio/2012/main";
    private static final String REL_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String ANCHOR_FILL = "#4CAF50";
    private static final String DEFAULT_FILL = "#2196F3";
    private static final String LINE_COLOR = "#44546A";

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

        writer.addAttribute("xmlns", VISIO_NS);
        writer.addAttribute("xmlns:r", REL_NS);

        if (!page.getShapes().isEmpty() || !page.getConnects().isEmpty()) {
            writer.startNode("Shapes");
            for (VisioShape shape : page.getShapes()) {
                writeRegularShape(writer, shape);
            }

            long connectorId = maximumShapeId(page);
            for (VisioConnect connect : page.getConnects()) {
                connectorId++;
                writeConnectorShape(writer, connectorId, connect);
            }
            writer.endNode();
        }

        if (!page.getConnects().isEmpty()) {
            writer.startNode("Connects");
            long connectorId = maximumShapeId(page);
            for (VisioConnect connect : page.getConnects()) {
                connectorId++;
                // Dynamic glue from a routable 1-D endpoint to a 2-D shape pin is
                // represented by one BeginX/EndX Connect row per endpoint. Visio
                // couples the corresponding Y coordinate; explicit BeginY/EndY
                // rows are reserved for other glue targets such as guides.
                writeGlue(writer, connectorId, "BeginX", connect.getFromShape());
                writeGlue(writer, connectorId, "EndX", connect.getToShape());
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

    private static void writeRegularShape(
            HierarchicalStreamWriter writer,
            VisioShape shape) {
        writer.startNode("Shape");
        writer.addAttribute("ID", shape.getId());
        writer.addAttribute("NameU", "TaxonomyShape." + shape.getId());
        writer.addAttribute("IsCustomNameU", "1");
        writer.addAttribute("Name", shape.getText());
        writer.addAttribute("IsCustomName", "1");
        writer.addAttribute("Type", "Shape");

        writeCell(writer, "PinX", decimal(shape.getX()));
        writeCell(writer, "PinY", decimal(shape.getY()));
        writeCell(writer, "Width", decimal(shape.getWidth()));
        writeCell(writer, "Height", decimal(shape.getHeight()));
        writeCell(writer, "LocPinX", decimal(shape.getWidth() / 2.0), "Width*0.5");
        writeCell(writer, "LocPinY", decimal(shape.getHeight() / 2.0), "Height*0.5");
        writeCell(writer, "Angle", "0");
        writeCell(writer, "FillForegnd", shape.isAnchor() ? ANCHOR_FILL : DEFAULT_FILL);
        writeCell(writer, "FillPattern", "1");
        writeCell(writer, "LineColor", LINE_COLOR);
        writeCell(writer, "LinePattern", "1");
        writeCell(writer, "LineWeight", "0.01388888888888889");
        writeCell(writer, "VerticalAlign", "1");
        writeRectangleGeometry(writer, shape.getWidth(), shape.getHeight());

        writer.startNode("Text");
        writer.setValue(shape.getText());
        writer.endNode();
        writer.endNode();
    }

    private static void writeConnectorShape(
            HierarchicalStreamWriter writer,
            long connectorId,
            VisioConnect connect) {
        writer.startNode("Shape");
        writer.addAttribute("ID", String.valueOf(connectorId));
        writer.addAttribute("NameU", "TaxonomyConnector." + connectorId);
        writer.addAttribute("IsCustomNameU", "1");
        writer.addAttribute("Type", "Shape");

        writeCell(writer, "OneD", "1");
        // A masterless custom connector is not automatically recognised as the
        // built-in Dynamic Connector. Mark it explicitly routable and allow
        // walking/dynamic glue before connecting BeginX/EndX to 2-D shape pins.
        writeCell(writer, "ObjType", "2");
        writeCell(writer, "GlueType", "2");
        writeCell(writer, "BeginX", "0");
        writeCell(writer, "BeginY", "0");
        writeCell(writer, "EndX", "1");
        writeCell(writer, "EndY", "1");
        writeCell(writer, "Width", "1", "GUARD(EndX-BeginX)");
        writeCell(writer, "Height", "1", "GUARD(EndY-BeginY)");
        writeCell(writer, "LocPinX", "0.5", "Width*0.5");
        writeCell(writer, "LocPinY", "0.5", "Height*0.5");
        writeCell(writer, "Angle", "0");
        writeCell(writer, "LineColor", LINE_COLOR);
        writeCell(writer, "LinePattern", "1");
        writeCell(writer, "LineWeight", "0.01388888888888889");
        writeCell(writer, "EndArrow", "13");
        writeCell(writer, "EndArrowSize", "2");
        writeLineGeometry(writer);

        writer.startNode("Text");
        writer.setValue(connect.getRelationType());
        writer.endNode();
        writer.endNode();
    }

    private static void writeGlue(
            HierarchicalStreamWriter writer,
            long connectorId,
            String fromCell,
            String endpointShapeId) {
        writer.startNode("Connect");
        writer.addAttribute("FromSheet", String.valueOf(connectorId));
        writer.addAttribute("FromCell", fromCell);
        writer.addAttribute("ToSheet", endpointShapeId);
        writer.addAttribute("ToCell", "PinX");
        writer.endNode();
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

    private static void writeLineGeometry(HierarchicalStreamWriter writer) {
        writer.startNode("Section");
        writer.addAttribute("N", "Geometry");
        writer.addAttribute("IX", "0");
        writeGeometryRow(writer, "MoveTo", 1, 0, 0, null, null);
        writeGeometryRow(writer, "LineTo", 2, 1, 1, "Width", "Height");
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
