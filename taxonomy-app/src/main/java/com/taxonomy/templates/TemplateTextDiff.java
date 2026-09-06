package com.taxonomy.templates;

import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.diff.SequenceComparator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded, lossless display adapter for JGit's existing text comparison algorithm. */
public final class TemplateTextDiff {
    static final int MAX_CHARACTERS = 131_072;
    static final int MAX_SEGMENTS = 2_048;

    private TemplateTextDiff() { }

    static Result compare(String before, String after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (before.length() > MAX_CHARACTERS || after.length() > MAX_CHARACTERS) {
            return new Result(true, List.of());
        }
        Segments left = split(before);
        Segments right = split(after);
        if (left == null || right == null) return new Result(true, List.of());
        var edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(COMPARATOR, left, right);
        List<Row> rows = new ArrayList<>();
        int a = 0;
        int b = 0;
        for (Edit edit : edits) {
            while (a < edit.getBeginA()) {
                rows.add(new Row("CONTEXT", ++a, ++b, left.values.get(a - 1)));
            }
            while (a < edit.getEndA()) {
                rows.add(new Row("DELETED", ++a, null, left.values.get(a - 1)));
            }
            while (b < edit.getEndB()) {
                rows.add(new Row("ADDED", null, ++b, right.values.get(b - 1)));
            }
        }
        while (a < left.size()) {
            rows.add(new Row("CONTEXT", ++a, ++b, left.values.get(a - 1)));
        }
        return new Result(false, List.copyOf(rows));
    }

    /**
     * Split before literal '<' and after LF without removing or normalizing any character.
     * These are display segments, not XML parser events or original source-line numbers.
     * Comments, CDATA, entities, whitespace and CRLF are retained verbatim. No XML is parsed
     * or executed, and joining the segments reproduces the exact supplied string.
     */
    private static Segments split(String text) {
        List<String> values = new ArrayList<>();
        int start = 0;
        for (int i = 1; i < text.length(); i++) {
            if (text.charAt(i) == '<' || text.charAt(i - 1) == '\n') {
                values.add(text.substring(start, i));
                if (values.size() >= MAX_SEGMENTS) return null;
                start = i;
            }
        }
        if (start < text.length()) values.add(text.substring(start));
        return new Segments(values);
    }

    private static final SequenceComparator<Segments> COMPARATOR = new SequenceComparator<>() {
        @Override
        public boolean equals(Segments a, int ai, Segments b, int bi) {
            return a.values.get(ai).equals(b.values.get(bi));
        }

        @Override
        public int hash(Segments sequence, int index) {
            return sequence.values.get(index).hashCode();
        }
    };

    private static final class Segments extends Sequence {
        private final List<String> values;

        private Segments(List<String> values) { this.values = values; }

        @Override
        public int size() { return values.size(); }
    }

    public record Row(String kind, Integer beforeNumber, Integer afterNumber, String text) { }
    public record Result(boolean limited, List<Row> rows) { }
}
