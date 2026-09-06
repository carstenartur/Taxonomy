package com.taxonomy.templates;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TemplateTextDiffTest {
    @Test
    void minifiedXmlExposesTheChangedTextWithoutRewritingItsSurroundings() {
        var result = verify("<w:p><w:t>before</w:t></w:p>", "<w:p><w:t>after</w:t></w:p>");
        assertTrue(result.rows().stream().anyMatch(row -> row.kind().equals("DELETED")
                && row.text().equals("<w:t>before")));
        assertTrue(result.rows().stream().anyMatch(row -> row.kind().equals("ADDED")
                && row.text().equals("<w:t>after")));
        assertTrue(result.rows().stream().anyMatch(row -> row.kind().equals("CONTEXT")));
    }

    @Test
    void additionsRemovalsAndEmptyFilesHaveDistinctSides() {
        assertTrue(verify("", "<a/>").rows().stream().allMatch(row -> row.kind().equals("ADDED")));
        assertTrue(verify("<a/>", "").rows().stream().allMatch(row -> row.kind().equals("DELETED")));
        assertTrue(verify("", "").rows().isEmpty());
    }

    @Test
    void commentsCdataWhitespaceEntitiesAndUnicodeRemainLiteral() {
        String text = "<?xml version=\"1.0\"?>\r\n<r><!-- <tag> -->"
                + "<![CDATA[<script>alert('x')</script>]]><v xml:space=\"preserve\"> ä 😀 &amp; </v></r>\n";
        var result = verify(text, text.replace(" ä ", " ö "));
        assertFalse(result.rows().isEmpty());
        assertTrue(verify(text, text).rows().stream().allMatch(row -> row.kind().equals("CONTEXT")));
    }

    @Test
    void trailingNewlineAndCrLfDifferencesAreNotHidden() {
        for (var pair : List.of(new String[]{"<a/>", "<a/>\n"},
                new String[]{"<a/>\r\n", "<a/>\n"}, new String[]{" ", ""})) {
            assertTrue(verify(pair[0], pair[1]).rows().stream()
                    .anyMatch(row -> !row.kind().equals("CONTEXT")));
        }
    }

    @Test
    void equalHashesDoNotMakeDifferentTextEqual() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        var result = verify("Aa", "BB");
        assertEquals(List.of("DELETED", "ADDED"), result.rows().stream().map(TemplateTextDiff.Row::kind).toList());
    }

    @Test
    void bothInputLimitsAreExactAndNeverReturnPartialDiffs() {
        assertFalse(TemplateTextDiff.compare("x".repeat(TemplateTextDiff.MAX_CHARACTERS), "").limited());
        assertFalse(TemplateTextDiff.compare("<x>".repeat(TemplateTextDiff.MAX_SEGMENTS), "").limited());
        for (String large : List.of("x".repeat(TemplateTextDiff.MAX_CHARACTERS + 1),
                "<x>".repeat(TemplateTextDiff.MAX_SEGMENTS + 1))) {
            for (var pair : List.of(new String[]{large, ""}, new String[]{"", large})) {
                var result = TemplateTextDiff.compare(pair[0], pair[1]);
                assertTrue(result.limited());
                assertTrue(result.rows().isEmpty());
            }
        }
    }

    @Test
    void manyMixedInputsReconstructBothSidesAndHaveConsecutiveDisplayNumbers() {
        var random = new Random(831);
        String[] fragments = {"<a>", "</a>", " ", "\n", "\r\n", "&lt;", "😀", "<!--", "]]>", "value"};
        for (int run = 0; run < 100; run++) {
            StringBuilder a = new StringBuilder();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                a.append(fragments[random.nextInt(fragments.length)]);
                b.append(fragments[random.nextInt(fragments.length)]);
            }
            verify(a.toString(), b.toString());
        }
    }

    @Test
    void resultRowsCannotBeMutated() {
        var result = verify("<a/>", "<b/>");
        assertThrows(UnsupportedOperationException.class, () -> result.rows().clear());
        assertThrows(NullPointerException.class, () -> TemplateTextDiff.compare(null, ""));
    }

    private static TemplateTextDiff.Result verify(String a, String b) {
        var result = TemplateTextDiff.compare(a, b);
        assertFalse(result.limited());
        var left = result.rows().stream().filter(row -> row.beforeNumber() != null).toList();
        var right = result.rows().stream().filter(row -> row.afterNumber() != null).toList();
        assertEquals(a, left.stream().map(TemplateTextDiff.Row::text).collect(Collectors.joining()));
        assertEquals(b, right.stream().map(TemplateTextDiff.Row::text).collect(Collectors.joining()));
        assertEquals(IntStream.rangeClosed(1, left.size()).boxed().toList(),
                left.stream().map(TemplateTextDiff.Row::beforeNumber).toList());
        assertEquals(IntStream.rangeClosed(1, right.size()).boxed().toList(),
                right.stream().map(TemplateTextDiff.Row::afterNumber).toList());
        return result;
    }
}
