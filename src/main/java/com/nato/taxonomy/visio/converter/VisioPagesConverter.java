package com.nato.taxonomy.visio.converter;

import com.nato.taxonomy.visio.VisioDocument;
import com.nato.taxonomy.visio.VisioPage;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

/**
 * XStream {@link Converter} that marshals a {@link VisioDocument} into the
 * {@code pages.xml} XML structure used inside a Visio {@code .vsdx} package.
 */
public class VisioPagesConverter implements Converter {

    private static final String VISIO_NS = "http://schemas.microsoft.com/office/visio/2012/main";
    private static final String REL_NS   = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    @Override
    public boolean canConvert(Class type) {
        return VisioDocument.class.equals(type);
    }

    @Override
    public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
        VisioDocument doc = (VisioDocument) source;
        writer.addAttribute("xmlns", VISIO_NS);
        writer.addAttribute("xmlns:r", REL_NS);

        for (int i = 0; i < doc.getPages().size(); i++) {
            VisioPage page = doc.getPages().get(i);
            writer.startNode("Page");
            writer.addAttribute("ID", String.valueOf(i));
            writer.addAttribute("Name", page.getName());
            writer.startNode("Rel");
            writer.addAttribute("r:id", "rId" + (i + 1));
            writer.endNode();
            writer.endNode();
        }
    }

    @Override
    public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
        throw new UnsupportedOperationException("VisioPagesConverter does not support unmarshaling");
    }
}
