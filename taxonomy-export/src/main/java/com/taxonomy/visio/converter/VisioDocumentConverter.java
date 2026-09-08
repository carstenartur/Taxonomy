package com.taxonomy.visio.converter;

import com.taxonomy.visio.VisioDocument;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

/**
 * Marshals the Visio main document part for the supported 2012 namespace.
 * Package metadata is stored in the standard OPC core-properties part rather
 * than in legacy inline document-property elements. Optional collections are
 * omitted when empty instead of emitting schema-invalid empty containers.
 */
public class VisioDocumentConverter implements Converter {

    private static final String VISIO_NS =
            "http://schemas.microsoft.com/office/visio/2012/main";
    private static final String REL_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    @Override
    public boolean canConvert(Class type) {
        return VisioDocument.class.equals(type);
    }

    @Override
    public void marshal(
            Object source,
            HierarchicalStreamWriter writer,
            MarshallingContext context) {
        writer.addAttribute("xmlns", VISIO_NS);
        writer.addAttribute("xmlns:r", REL_NS);
        writer.addAttribute("xml:space", "preserve");

        // Explicit defaults make the masterless package renderable as well as parseable.
        writer.startNode("DocumentSettings");
        writer.addAttribute("DefaultLineStyle", "0");
        writer.addAttribute("DefaultFillStyle", "0");
        writer.addAttribute("DefaultTextStyle", "0");
        writer.endNode();
        writer.startNode("FaceNames");
        writer.startNode("FaceName");
        writer.addAttribute("ID", "0");
        writer.addAttribute("NameU", "Arial");
        writer.endNode();
        writer.endNode();
        writer.startNode("StyleSheets");
        writer.startNode("StyleSheet");
        writer.addAttribute("ID", "0");
        writer.addAttribute("NameU", "Normal");
        cell(writer, "LineColor", "#44546A");
        cell(writer, "LinePattern", "1");
        cell(writer, "LineWeight", "0.01388888888888889");
        cell(writer, "LineCap", "0");
        cell(writer, "FillForegnd", "#FFFFFF");
        cell(writer, "FillBkgnd", "#FFFFFF");
        cell(writer, "FillPattern", "1");
        cell(writer, "VerticalAlign", "1");
        writer.startNode("Section");
        writer.addAttribute("N", "Character");
        writer.startNode("Row");
        writer.addAttribute("IX", "0");
        cell(writer, "Font", "0");
        cell(writer, "Size", "0.1388888888888889");
        cell(writer, "Color", "#172B4D");
        writer.endNode();
        writer.endNode();
        writer.startNode("Section");
        writer.addAttribute("N", "Paragraph");
        writer.startNode("Row");
        writer.addAttribute("IX", "0");
        cell(writer, "HorzAlign", "1");
        writer.endNode();
        writer.endNode();
        writer.endNode();
        writer.endNode();
    }

    private static void cell(HierarchicalStreamWriter writer, String name, String value) {
        writer.startNode("Cell");
        writer.addAttribute("N", name);
        writer.addAttribute("V", value);
        writer.endNode();
    }

    @Override
    public Object unmarshal(
            HierarchicalStreamReader reader,
            UnmarshallingContext context) {
        throw new UnsupportedOperationException(
                "VisioDocumentConverter does not support unmarshaling");
    }
}
