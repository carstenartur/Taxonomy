package com.taxonomy.export;

import org.apache.pdfbox.pdmodel.font.PDFont;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Measures the actual Standard-14 glyphs after transliteration, before choosing line breaks. */
final class PdfDiagramText {
    private PdfDiagramText() { }

    static String printable(String value) {
        String text = value == null || value.isBlank() ? "n/a" : value.strip();
        text = text.replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace('–', '-').replace('—', '-').replace("…", "...").replace('·', '|');
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replaceAll("[^\\x20-\\x7E]", "?");
    }

    static float width(PDFont font, float size, String printable) throws IOException {
        return font.getStringWidth(printable) / 1000f * size;
    }

    static List<String> wrap(String value, PDFont font, float size, float maximum, int limit) throws IOException {
        if (!(maximum > 0) || !(size > 0) || limit < 1) throw new IllegalArgumentException("Invalid text bounds");
        String rest = printable(value);
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

    private static int fittingPrefix(String value, PDFont font, float size, float maximum) throws IOException {
        int low = 0, high = value.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (width(font, size, value.substring(0, middle)) <= maximum) low = middle;
            else high = middle - 1;
        }
        return low;
    }
}
