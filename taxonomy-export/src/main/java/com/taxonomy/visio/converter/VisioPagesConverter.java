package com.taxonomy.visio.converter;

import com.taxonomy.diagram.VisioDrawingOptions;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioPageLayout;
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
            writer.addAttribute("ID", page.getId());
            writer.addAttribute("Name", page.getName());
            writer.addAttribute("NameU", "TaxonomyPage." + page.getId());
            writer.addAttribute("IsCustomName", "1");
            writer.addAttribute("IsCustomNameU", "1");

            writer.startNode("PageSheet");
            var layout = VisioPageLayout.of(page, document.getDrawingOptions());
            writeCell(writer, "PageWidth", decimal(layout.width()));
            writeCell(writer, "PageHeight", decimal(layout.height()));
            writeCell(writer, "PageScale", "1");
            writeCell(writer, "DrawingScale", "1");
            if (document.getDrawingOptions() != null && document.getDrawingOptions().mode() == VisioDrawingOptions.Mode.WALL) {
                writeCell(writer, "PrintPageOrientation", "2"); // visPPOLandscape
                writeCell(writer, "OnPage", "1");
                writeCell(writer, "PagesX", "1");
                writeCell(writer, "PagesY", "1");
                // Windows DMPAPER codes. A0 and A1 media are printer-specific.
                switch (document.getDrawingOptions().paper()) {
                    case A4 -> writeCell(writer, "PaperKind", "9");
                    case A3 -> writeCell(writer, "PaperKind", "8");
                    case A2 -> writeCell(writer, "PaperKind", "66");
                    case A1, A0 -> { }
                }
            }
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
