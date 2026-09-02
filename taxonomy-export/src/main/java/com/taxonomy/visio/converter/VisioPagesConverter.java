package com.taxonomy.visio.converter;

import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioPage;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

import java.math.BigDecimal;

/** Marshals the Visio page index part used inside a {@code .vsdx} package. */
public class VisioPagesConverter implements Converter {

    private static final String VISIO_NS =
            "http://schemas.microsoft.com/office/visio/2012/main";
    private static final String REL_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final double MINIMUM_PAGE_WIDTH = 11.0;
    private static final double MINIMUM_PAGE_HEIGHT = 8.5;
    private static final double PAGE_MARGIN = 1.0;

    @Override
    public boolean canConvert(Class type) {
        return VisioDocument.class.equals(type);
    }

    @Override
    public void marshal(
            Object source,
            HierarchicalStreamWriter writer,
            MarshallingContext context) {
        VisioDocument document = (VisioDocument) source;
        writer.addAttribute("xmlns", VISIO_NS);
        writer.addAttribute("xmlns:r", REL_NS);

        for (int index = 0; index < document.getPages().size(); index++) {
            VisioPage page = document.getPages().get(index);
            writer.startNode("Page");
            writer.addAttribute("ID", String.valueOf(index));
            writer.addAttribute("Name", page.getName());
            writer.addAttribute("NameU", "TaxonomyPage." + index);
            writer.addAttribute("IsCustomName", "1");
            writer.addAttribute("IsCustomNameU", "1");

            writer.startNode("PageSheet");
            writeCell(writer, "PageWidth", decimal(pageWidth(page)));
            writeCell(writer, "PageHeight", decimal(pageHeight(page)));
            writeCell(writer, "PageScale", "1");
            writeCell(writer, "DrawingScale", "1");
            writer.endNode();

            writer.startNode("Rel");
            writer.addAttribute("r:id", "rId" + (index + 1));
            writer.endNode();
            writer.endNode();
        }
    }

    @Override
    public Object unmarshal(
            HierarchicalStreamReader reader,
            UnmarshallingContext context) {
        throw new UnsupportedOperationException(
                "VisioPagesConverter does not support unmarshaling");
    }

    private static double pageWidth(VisioPage page) {
        double right = page.getShapes().stream()
                .mapToDouble(shape -> shape.getX() + shape.getWidth() / 2.0)
                .max()
                .orElse(0.0);
        return Math.max(MINIMUM_PAGE_WIDTH, right + PAGE_MARGIN);
    }

    private static double pageHeight(VisioPage page) {
        double top = page.getShapes().stream()
                .mapToDouble(shape -> shape.getY() + shape.getHeight() / 2.0)
                .max()
                .orElse(0.0);
        return Math.max(MINIMUM_PAGE_HEIGHT, top + PAGE_MARGIN);
    }

    private static void writeCell(
            HierarchicalStreamWriter writer,
            String name,
            String value) {
        writer.startNode("Cell");
        writer.addAttribute("N", name);
        writer.addAttribute("V", value);
        writer.endNode();
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
