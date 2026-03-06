package com.nato.taxonomy.visio.converter;

import com.nato.taxonomy.visio.VisioDocument;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

/**
 * XStream {@link Converter} that marshals a {@link VisioDocument} into the
 * {@code document.xml} XML structure used inside a Visio {@code .vsdx} package.
 */
public class VisioDocumentConverter implements Converter {

    private static final String VISIO_NS = "http://schemas.microsoft.com/office/visio/2012/main";
    private static final String REL_NS   = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    @Override
    public boolean canConvert(Class type) {
        return VisioDocument.class.equals(type);
    }

    @Override
    public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
        writer.addAttribute("xmlns", VISIO_NS);
        writer.addAttribute("xmlns:r", REL_NS);
        writer.addAttribute("xml:space", "preserve");

        writer.startNode("DocumentProperties");
        writer.startNode("Creator");
        writer.setValue("NATO NC3T Taxonomy Browser");
        writer.endNode();
        writer.startNode("Description");
        writer.setValue("Architecture diagram generated from requirement analysis");
        writer.endNode();
        writer.endNode();

        writer.startNode("DocumentSettings");
        writer.endNode();

        writer.startNode("FaceNames");
        writer.endNode();

        writer.startNode("StyleSheets");
        writer.endNode();
    }

    @Override
    public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
        throw new UnsupportedOperationException("VisioDocumentConverter does not support unmarshaling");
    }
}
