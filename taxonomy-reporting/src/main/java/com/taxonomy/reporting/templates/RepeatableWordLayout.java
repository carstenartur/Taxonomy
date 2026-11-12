package com.taxonomy.reporting.templates;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Version-one author-owned paragraph/table exemplars in the main Word story. */
public final class RepeatableWordLayout {
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String CHAPTERS = "taxonomy.repeat.chapters.v1";
    private static final String APPENDIX = "taxonomy.repeat.appendix.v1";
    private static final long MAX_CLONED_ELEMENTS = 120_000;
    private static final long MAX_CLONED_TEXT = 8_000_000;
    private static final String FIELD = "taxonomy.field.";
    private static final Set<String> CHAPTER_FIELDS = Set.of("number", "code", "title", "score", "summary", "comparison");
    private static final Set<String> APPENDIX_FIELDS = Set.of("code", "title", "score", "path", "reason");
    private static final Set<String> UNREPEATABLE = Set.of("dataBinding", "drawing", "pict", "object", "altChunk",
            "txbxContent", "customXml", "fldSimple", "instrText", "bookmarkStart", "bookmarkEnd", "sectPr",
            "hyperlink", "commentRangeStart", "commentRangeEnd", "footnoteReference", "endnoteReference", "ins", "del");
    private static final Pattern TOKEN = Pattern.compile("\\{\\{taxonomy\\.[A-Za-z0-9.]+}}");
    private final Map<String, Element> exemplars = new LinkedHashMap<>();

    static void validate(Document document, String path) {
        discover(document, path);
    }

    public byte[] extract(byte[] xml) {
        var doc = DecisionRationaleTemplateContract.parse("word/document.xml", xml);
        var controls = discover(doc, "word/document.xml");
        if (controls.isEmpty()) return xml;
        controls.forEach((name, control) -> {
            exemplars.put(name, direct(control, "sdtContent"));
            control.getParentNode().removeChild(control);
        });
        return serialize(doc);
    }

    public boolean isEmpty() { return exemplars.isEmpty(); }

    private static Map<String, Element> discover(Document doc, String path) {
        var layouts = new LinkedHashMap<String, Element>();
        if (!descendants(doc.getDocumentElement(), "dataBinding").isEmpty()) throw invalid();
        for (var control : descendants(doc.getDocumentElement(), "sdt")) {
            String tag = tag(control);
            if (!tag.startsWith("taxonomy.")) continue;
            if (!"word/document.xml".equals(path)) throw invalid();
            if (tag.startsWith(FIELD)) {
                Element owner = ancestorControl(control);
                if (owner == null || !Set.of(CHAPTERS, APPENDIX).contains(tag(owner))) throw invalid();
                continue;
            }
            if (!Set.of(CHAPTERS, APPENDIX).contains(tag) || !(control.getParentNode() instanceof Element parent)
                    || !word(parent, "body") || layouts.putIfAbsent(tag, control) != null) throw invalid();
            Element content = direct(control, "sdtContent");
            if (content == null) throw invalid();
            for (Node child = content.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element block && !word(block, "p") && !word(block, "tbl")) throw invalid();
            }
            for (String forbidden : UNREPEATABLE) if (!descendants(control, forbidden).isEmpty()) throw invalid();
            var fields = descendants(content, "sdt");
            if (fields.isEmpty()) throw invalid();
            Set<String> names = CHAPTERS.equals(tag) ? CHAPTER_FIELDS : APPENDIX_FIELDS;
            for (var field : fields) {
                String name = tag(field);
                var value = direct(field, "sdtContent");
                if (!name.startsWith(FIELD) || !names.contains(name.substring(FIELD.length()))
                        || ancestorControl(field) != control || !(field.getParentNode() instanceof Element p)
                        || !word(p, "p") || value == null || descendants(value, "t").isEmpty()) throw invalid();
                for (Node child = value.getFirstChild(); child != null; child = child.getNextSibling())
                    if (child instanceof Element run && !word(run, "r")) throw invalid();
            }
        }
        return layouts;
    }

    public byte[] materialize(byte[] xml, DecisionRationaleReport report) {
        if (isEmpty()) return xml;
        var doc = DecisionRationaleTemplateContract.parse("word/document.xml", xml);
        // A section absent from the frozen format options has no generated bookmark.
        long clonedElements = 0;
        long clonedText = 0;
        for (var bookmark : descendants(doc.getDocumentElement(), "bookmarkStart")) {
            String name = bookmark.getAttributeNS(WORD, "name");
            boolean chapters = name.equals("decision_chapters");
            if (!chapters && !name.equals("decision_evidence")) continue;
            var exemplar = exemplars.get(chapters ? CHAPTERS : APPENDIX);
            if (exemplar == null) continue;
            int copies = chapters ? report.chapters().size() : report.leadingLeaves().size();
            clonedElements += (long) copies * exemplar.getElementsByTagName("*").getLength();
            clonedText += (long) copies * exemplar.getTextContent().length();
            if (clonedElements > MAX_CLONED_ELEMENTS || clonedText > MAX_CLONED_TEXT)
                throw new IllegalArgumentException("The repeated Word layout exceeds its materialization budget");
            Node heading = bookmark.getParentNode();
            while (heading instanceof Element e && !word(e, "p")) heading = heading.getParentNode();
            if (heading == null) throw invalid();
            Node before = heading.getNextSibling();
            if (chapters) {
                for (var row : report.chapters()) clonedText = insert(exemplar, heading.getParentNode(), before, clonedText,
                        Map.of("number", String.valueOf(row.number()), "code", value(row.parentCode()),
                                "title", value(row.parentTitle()), "score", value(row.parentScore()),
                                "summary", value(row.decisionSummary()), "comparison", value(row.comparativeRationale())));
            } else {
                for (var row : report.leadingLeaves()) clonedText = insert(exemplar, heading.getParentNode(), before, clonedText,
                        Map.of("code", value(row.code()), "title", value(row.title()), "score", value(row.score()),
                                "path", value(row.hierarchyPath()), "reason", value(row.reason())));
            }
        }
        return serialize(doc);
    }

    private static long insert(Element exemplar, Node body, Node before, long clonedText, Map<String, String> values) {
        var clone = (Element) body.getOwnerDocument().importNode(exemplar, true);
        for (var field : descendants(clone, "sdt")) {
            var content = direct(field, "sdtContent");
            var texts = descendants(content, "t");
            texts.forEach(text -> text.setTextContent(""));
            String replacement = values.get(tag(field).substring(FIELD.length()));
            clonedText += replacement.length();
            if (clonedText > MAX_CLONED_TEXT) throw new IllegalArgumentException("Repeated Word text exceeds its materialization budget");
            texts.getFirst().setTextContent(replacement);
            texts.getFirst().setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
            while (content.hasChildNodes()) field.getParentNode().insertBefore(content.getFirstChild(), field);
            field.getParentNode().removeChild(field);
        }
        while (clone.hasChildNodes()) body.insertBefore(clone.getFirstChild(), before);
        return clonedText;
    }

    /** Replace only token characters. Each replacement inherits the first matching text run. */
    public static List<String> replaceRuns(List<String> pieces, Map<String, String> values) {
        String joined = String.join("", pieces);
        var matches = TOKEN.matcher(joined).results().filter(m -> values.containsKey(m.group())).toList();
        if (matches.isEmpty()) return pieces;
        var result = new ArrayList<>(pieces);
        int[] starts = new int[pieces.size() + 1];
        for (int i = 0; i < pieces.size(); i++) starts[i + 1] = starts[i] + pieces.get(i).length();
        for (var match : matches.reversed()) {
            for (int i = 0; i < pieces.size(); i++) {
                int from = Math.max(match.start(), starts[i]), to = Math.min(match.end(), starts[i + 1]);
                if (from >= to) continue;
                String text = result.get(i);
                result.set(i, text.substring(0, from - starts[i])
                        + (from == match.start() ? values.get(match.group()) : "") + text.substring(to - starts[i]));
            }
        }
        return result;
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }

    private static Element ancestorControl(Element control) {
        for (Node p = control.getParentNode(); p instanceof Element e; p = p.getParentNode()) if (word(e, "sdt")) return e;
        return null;
    }

    private static String tag(Element control) {
        var properties = direct(control, "sdtPr");
        var tag = properties == null ? null : direct(properties, "tag");
        return tag == null ? "" : tag.getAttributeNS(WORD, "val");
    }

    private static Element direct(Element parent, String local) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element element && word(element, local)) return element;
        return null;
    }

    private static boolean word(Element element, String name) {
        return WORD.equals(element.getNamespaceURI()) && name.equals(element.getLocalName());
    }

    private static List<Element> descendants(Element root, String name) {
        var nodes = root.getElementsByTagNameNS(WORD, name);
        var elements = new ArrayList<Element>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) elements.add((Element) nodes.item(i));
        return elements;
    }

    private static byte[] serialize(Document doc) {
        try {
            var factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var output = new ByteArrayOutputStream();
            factory.newTransformer().transform(new DOMSource(doc), new StreamResult(output));
            return output.toByteArray();
        } catch (javax.xml.transform.TransformerException failed) {
            throw new IllegalStateException("Could not materialize the Word layout", failed);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Use an unbound version-one chapter or appendix control in the main Word body with supported inline fields");
    }
}
