package com.taxonomy.visio.converter;

import com.taxonomy.visio.VisioConnect;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioShape;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

/**
 * XStream {@link Converter} that marshals a {@link VisioPage} into the
 * {@code PageContents} XML structure used for individual page files inside a
 * Visio {@code .vsdx} package.
 * <p>
 * Anchor shapes receive the fill colour {@code #4CAF50} (green); all other shapes
 * receive {@code #2196F3} (blue). Connector shape IDs are allocated sequentially
 * starting after the last regular shape ID.
 */
public class VisioPageContentsConverter implements Converter {

    private static final String VISIO_NS     = "http://schemas.microsoft.com/office/visio/2012/main";
    private static final String REL_NS       = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String ANCHOR_FILL  = "#4CAF50";
    private static final String DEFAULT_FILL = "#2196F3";

    @Override
    public boolean canConvert(Class type) {
        return VisioPage.class.equals(type);
    }

    @Override
    public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
        VisioPage page = (VisioPage) source;

        writer.addAttribute("xmlns", VISIO_NS);
        writer.addAttribute("xmlns:r", REL_NS);

        // Regular shapes
        for (VisioShape shape : page.getShapes()) {
            writer.startNode("Shape");
            writer.addAttribute("ID", shape.getId());
            writer.addAttribute("NameU", shape.getText());
            writer.addAttribute("Type", "Shape");
            writeCell(writer, "PinX", String.valueOf(shape.getX()));
            writeCell(writer, "PinY", String.valueOf(shape.getY()));
            writeCell(writer, "Width", String.valueOf(shape.getWidth()));
            writeCell(writer, "Height", String.valueOf(shape.getHeight()));
            writeCell(writer, "FillForegnd", shape.isAnchor() ? ANCHOR_FILL : DEFAULT_FILL);
            writer.startNode("Text");
            writer.setValue(shape.getText());
            writer.endNode();
            writer.endNode();
        }

        // Connector shapes
        int connectorId = page.getShapes().size();
        for (VisioConnect connect : page.getConnects()) {
            connectorId++;
            writer.startNode("Shape");
            writer.addAttribute("ID", String.valueOf(connectorId));
            writer.addAttribute("Type", "Shape");
            writer.addAttribute("Master", "Dynamic connector");
            writeCell(writer, "BeginX", "0");
            writeCell(writer, "BeginY", "0");
            writeCell(writer, "EndX", "1");
            writeCell(writer, "EndY", "1");
            writer.startNode("Text");
            writer.setValue(connect.getRelationType());
            writer.endNode();
            writer.endNode();
        }

        // Connect elements
        connectorId = page.getShapes().size();
        for (VisioConnect connect : page.getConnects()) {
            connectorId++;
            writer.startNode("Connect");
            writer.addAttribute("FromSheet", String.valueOf(connectorId));
            writer.addAttribute("FromCell", "BeginX");
            writer.addAttribute("ToSheet", connect.getFromShape());
            writer.addAttribute("ToCell", "PinX");
            writer.endNode();

            writer.startNode("Connect");
            writer.addAttribute("FromSheet", String.valueOf(connectorId));
            writer.addAttribute("FromCell", "EndX");
            writer.addAttribute("ToSheet", connect.getToShape());
            writer.addAttribute("ToCell", "PinX");
            writer.endNode();
        }
    }

    @Override
    public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
        throw new UnsupportedOperationException("VisioPageContentsConverter does not support unmarshaling");
    }

    private void writeCell(HierarchicalStreamWriter writer, String name, String value) {
        writer.startNode("Cell");
        writer.addAttribute("N", name);
        writer.addAttribute("V", value);
        writer.endNode();
    }
}
