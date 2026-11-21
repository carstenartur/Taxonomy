package com.taxonomy.export;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Measures the embedded glyphs and retains paragraph boundaries in inventory text. */
final class PdfDiagramText {
    private PdfDiagramText() { }

    static PDFont loadFont(PDDocument document, boolean bold) throws IOException {
        String resource = bold ? "/fonts/DejaVuSans-Bold.ttf" : "/fonts/DejaVuSans.ttf";
        try (var content = PdfDiagramText.class.getResourceAsStream(resource)) {
            if (content == null) throw new IOException("Missing embedded PDF font " + resource);
            var font = new TTFParser().parseEmbedded(content);
            document.registerTrueTypeFontForClosing(font);
            // PDF extraction must retain source code points such as separate f and i.
            font.setEnableGsub(false);
            return PDType0Font.load(document, font, true);
        }
    }

    static String printable(String value, PDFont font) throws IOException {
        String text = value == null || value.isBlank() ? "n/a"
                : value.replaceAll("\\R", " ").replace('\t', ' ').strip();
        try {
            font.encode(text);
            return text;
        } catch (IllegalArgumentException unsupported) {
            var result = new StringBuilder();
            for (int offset = 0; offset < text.length();) {
                int codePoint = text.codePointAt(offset);
                String glyph = Character.toString(codePoint);
                try {
                    font.encode(glyph);
                    result.append(glyph);
                } catch (IllegalArgumentException missing) {
                    // Leave a recoverable identity for text outside the embedded font's repertoire.
                    result.append(String.format(Locale.ROOT, "[U+%04X]", codePoint));
                }
                offset += Character.charCount(codePoint);
            }
            return result.toString();
        }
    }

    static float width(PDFont font, float size, String printable) throws IOException {
        return font.getStringWidth(printable) / 1000f * size;
    }

    static List<String> wrap(String value, PDFont font, float size, float maximum, int limit) throws IOException {
        if (!(maximum > 0) || !(size > 0) || limit < 1) throw new IllegalArgumentException("Invalid text bounds");
        String rest = printable(value, font);
        List<String> lines = new ArrayList<>();
        while (!rest.isEmpty()) {
            if (width(font, size, rest) <= maximum) { lines.add(rest); break; }
            int end = fittingPrefix(rest, font, size, maximum);
            if (lines.size() + 1 == limit) {
                String ellipsis = "...";
                if (width(font, size, ellipsis) > maximum) throw new IllegalArgumentException("Box too narrow for readable text");
                end = fittingPrefix(rest, font, size, maximum - width(font, size, ellipsis));
                lines.add(rest.substring(0, end).stripTrailing() + ellipsis);
                break;
            }
            if (end == 0) throw new IllegalArgumentException("Box too narrow for readable text");
            int space = rest.lastIndexOf(' ', end);
            if (space > 0) end = space;
            lines.add(rest.substring(0, end).stripTrailing());
            rest = rest.substring(end).stripLeading();
        }
        return List.copyOf(lines);
    }

    static List<String> wrapParagraphs(String value, PDFont font, float size, float maximum) throws IOException {
        var lines = new ArrayList<String>();
        for (String paragraph : Objects.toString(value, "n/a").strip().split("\\R", -1)) {
            if (paragraph.isBlank()) lines.add("");
            else lines.addAll(wrap(paragraph, font, size, maximum, Integer.MAX_VALUE));
        }
        return lines;
    }

    private static int fittingPrefix(String value, PDFont font, float size, float maximum) throws IOException {
        int low = 0, high = value.codePointCount(0, value.length());
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (width(font, size, value.substring(0, value.offsetByCodePoints(0, middle))) <= maximum) low = middle;
            else high = middle - 1;
        }
        return value.offsetByCodePoints(0, low);
    }
}
