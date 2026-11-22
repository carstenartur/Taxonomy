package com.taxonomy.reporting.render.document;

import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.ImageRef;
import com.vladsch.flexmark.ast.InlineLinkNode;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.LinkRef;
import com.vladsch.flexmark.ast.ListBlock;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.RefNode;
import com.vladsch.flexmark.ast.Reference;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Presentation of authored requirement text. The source used for evidence remains untouched. */
public final class RequirementReportText {
    private static final Parser MARKDOWN = Parser.builder().build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder(new MutableDataSet()
            .set(HtmlRenderer.ESCAPE_HTML, true)
            .set(HtmlRenderer.DO_NOT_RENDER_LINKS, true)
            .set(HtmlRenderer.RENDER_HEADER_ID, false)
            .set(HtmlRenderer.SOFT_BREAK, "<br>\n"))
            .nodeRendererFactory(options -> () -> Set.of(
                    new NodeRenderingHandler<>(Link.class, RequirementReportText::htmlLink),
                    new NodeRenderingHandler<>(Image.class, RequirementReportText::htmlLink),
                    new NodeRenderingHandler<>(LinkRef.class, RequirementReportText::htmlLink),
                    new NodeRenderingHandler<>(ImageRef.class, RequirementReportText::htmlLink)))
            .build();

    private RequirementReportText() { }

    private record Block(Node content, int indentation, String prefix) { }

    public static String html(String source) {
        return HTML.render(MARKDOWN.parse(Objects.toString(source, "")));
    }

    public static String plain(String source) {
        return String.join("\n", blocks(source).stream()
                .map(block -> block.prefix() + visibleText(block.content()).strip())
                .toList());
    }

    public static void write(String source, Supplier<XWPFParagraph> paragraphs, int fontSize) {
        blocks(source).forEach(block -> writeBlock(block, paragraphs.get(), fontSize));
    }

    private static List<Block> blocks(String source) {
        var result = new ArrayList<Block>();
        for (Node block : MARKDOWN.parse(Objects.toString(source, "")).getChildren())
            addBlock(result, block, 0, "");
        return result;
    }

    private static void addBlock(List<Block> blocks, Node node, int level, String prefix) {
        if (node instanceof ListBlock) {
            int number = node instanceof OrderedList ordered ? ordered.getStartNumber() : 1;
            for (Node item : node.getChildren()) {
                String marker = node instanceof OrderedList ? number++ + ". " : "• ";
                for (Node child : item.getChildren()) {
                    addBlock(blocks, child, level + 1, marker);
                    marker = "";
                }
            }
        } else if (!(node instanceof ThematicBreak) && !(node instanceof Reference)) {
            blocks.add(new Block(node, level, prefix));
        }
    }

    private static void writeBlock(Block block, XWPFParagraph paragraph, int fontSize) {
        Node content = block.content();
        boolean heading = content instanceof Heading;
        paragraph.setIndentationLeft(block.indentation() * 220);
        if (heading) paragraph.setKeepNext(true);
        int size = heading ? fontSize + 1 : fontSize;
        run(paragraph, block.prefix(), size, false, false, false);
        if (heading || content instanceof Paragraph) {
            for (Node inline : content.getChildren()) writeInline(inline, paragraph, size, heading, false);
        } else {
            run(paragraph, visibleText(content), size, false, false, false);
        }
    }

    private static void writeInline(Node node, XWPFParagraph paragraph, int size, boolean bold, boolean italic) {
        if (node.hasChildren() && !(node instanceof Code)) {
            for (Node child : node.getChildren()) {
                writeInline(child, paragraph, size, bold || node instanceof StrongEmphasis,
                        italic || node instanceof Emphasis);
            }
            run(paragraph, destination(node), size, bold, italic, false);
        } else {
            run(paragraph, visibleText(node), size, bold, italic, node instanceof Code);
        }
    }

    private static String visibleText(Node node) {
        if (node instanceof SoftLineBreak || node instanceof HardLineBreak) return "\n";
        if (node instanceof Code) return text(node);
        if (!node.hasChildren()) return text(node) + destination(node);
        var result = new StringBuilder();
        for (Node child : node.getChildren()) result.append(visibleText(child));
        return result.append(destination(node)).toString();
    }

    private static void htmlLink(Node node, NodeRendererContext context, HtmlWriter html) {
        if (node.hasChildren()) context.renderChildren(node);
        else html.text(text(node));
        html.text(destination(node));
    }

    /** Display destinations as text in every format, including authored images. */
    private static String destination(Node node) {
        String address = switch (node) {
            case InlineLinkNode link -> link.getUrl().unescape();
            case RefNode link -> {
                var definition = link.getReferenceNode(link.getDocument());
                yield definition == null ? "" : definition.getUrl().unescape();
            }
            default -> "";
        };
        return address.isBlank() ? "" : " (" + address + ")";
    }

    private static void run(XWPFParagraph paragraph, String value, int size,
                            boolean bold, boolean italic, boolean code) {
        if (value.isEmpty()) return;
        var run = paragraph.createRun();
        run.setFontSize(size);
        run.setBold(bold);
        run.setItalic(italic);
        if (code) run.setFontFamily("Consolas");
        WordDocumentWriter.writeLines(run, value);
    }

    private static String text(Node node) {
        return new TextCollectingVisitor().collectAndGetText(node);
    }
}
