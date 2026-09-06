package com.taxonomy.templates;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TemplateTextDiffContextTest {
    @Test
    void distantUnchangedContentIsFoldedButEveryChangeRemainsVisible() {
        String before = "<w:p>unchanged</w:p>".repeat(100);
        String after = before + "<w:p>changed</w:p>";
        var result = TemplateTextDiff.compare(before, after);
        var blocks = verify(result);
        assertTrue(blocks.stream().anyMatch(TemplateTextDiff.Block::folded));
        long visibleContext = blocks.stream().filter(block -> !block.folded())
                .flatMap(block -> block.rows().stream())
                .filter(row -> row.kind().equals("CONTEXT")).count();
        assertTrue(visibleContext <= 6);
        assertTrue(blocks.stream().filter(block -> !block.folded())
                .flatMap(block -> block.rows().stream())
                .anyMatch(row -> row.text().contains("changed")));
    }

    @Test
    void separatedEditsRetainContextOnBothSidesAndFoldOnlyExcess() {
        var rows = new java.util.ArrayList<TemplateTextDiff.Row>();
        for (int i = 1; i <= 30; i++) {
            rows.add(new TemplateTextDiff.Row(i == 5 || i == 25 ? "ADDED" : "CONTEXT",
                    i == 5 || i == 25 ? null : i, i, "segment " + i));
        }
        var blocks = verify(new TemplateTextDiff.Result(false, List.copyOf(rows)));
        assertEquals(2, blocks.stream().filter(TemplateTextDiff.Block::folded).count());
        var middle = blocks.stream().filter(TemplateTextDiff.Block::folded)
                .filter(block -> block.rows().getFirst().afterNumber() > 5)
                .findFirst().orElseThrow();
        assertEquals(9, middle.rows().getFirst().afterNumber());
        assertEquals(21, middle.rows().getLast().afterNumber());
    }

    @Test
    void smallContextAndEntirelyAddedOrRemovedPartsStayVisible() {
        for (var pair : List.of(new String[]{"<a/>", "<a/><b/>"},
                new String[]{"", "<b/>".repeat(100)}, new String[]{"<b/>".repeat(100), ""})) {
            assertTrue(verify(TemplateTextDiff.compare(pair[0], pair[1])).stream()
                    .noneMatch(TemplateTextDiff.Block::folded));
        }
    }

    @Test
    void identicalEmptyAndLimitedResultsHaveNoInventedChanges() {
        var identical = verify(TemplateTextDiff.compare("<a/>".repeat(100), "<a/>".repeat(100)));
        assertEquals(1, identical.size());
        assertTrue(identical.getFirst().folded());
        assertTrue(verify(TemplateTextDiff.compare("", "")).isEmpty());
        assertTrue(verify(TemplateTextDiff.compare("x".repeat(TemplateTextDiff.MAX_CHARACTERS + 1), "")).isEmpty());
    }

    @Test
    void mixedUnicodeAndWhitespaceInputsRetainTheExactOrderedRows() {
        var random = new Random(831);
        String[] fragments = {"<a>", "</a>", " ", "\n", "\r\n", "&lt;", "😀", "<!--", "]]>", "value"};
        for (int run = 0; run < 100; run++) {
            var a = new StringBuilder();
            var b = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                String fragment = fragments[random.nextInt(fragments.length)];
                a.append(fragment);
                b.append(random.nextInt(20) == 0 ? "changed" : fragment);
            }
            verify(TemplateTextDiff.compare(a.toString(), b.toString()));
        }
    }

    @Test
    void presentationCollectionsCannotBeMutated() {
        var blocks = verify(TemplateTextDiff.compare("<a/>".repeat(100), "<a/>".repeat(100)));
        assertThrows(UnsupportedOperationException.class, blocks::clear);
        assertThrows(UnsupportedOperationException.class, () -> blocks.getFirst().rows().clear());
    }

    private static List<TemplateTextDiff.Block> verify(TemplateTextDiff.Result result) {
        var blocks = result.blocks();
        assertEquals(result.rows(), blocks.stream().flatMap(block -> block.rows().stream()).toList());
        for (var block : blocks) {
            assertFalse(block.rows().isEmpty());
            if (block.folded()) assertTrue(block.rows().stream().allMatch(row -> row.kind().equals("CONTEXT")));
        }
        return blocks;
    }
}
