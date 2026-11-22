package com.taxonomy.portfolio.dto;

import com.taxonomy.reformulation.Statement.SourceSpan;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.ListBlock;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Questions explicitly placed in a decision section of the immutable requirement. Offsets are UTF-16 source indices. */
public record RequirementDecisionSection(String heading, SourceSpan question, SourceSpan paragraph) {
    private static final Parser MARKDOWN = Parser.builder().build();

    public static List<RequirementDecisionSection> questions(String original) {
        if (original == null || original.indexOf('?') < 0) return List.of();
        String lower = original.toLowerCase(Locale.ROOT);
        if (!lower.contains("offene entscheidung") && !lower.contains("open decision")) return List.of();
        var questions = new ArrayList<RequirementDecisionSection>();
        String heading = null;
        for (Node block : MARKDOWN.parse(original).getChildren()) {
            if (block instanceof Heading title) {
                String label = title.getText().toString().strip();
                heading = switch (label.toLowerCase(Locale.ROOT)) {
                    case "offene entscheidung", "offene entscheidungen", "open decision", "open decisions" -> label;
                    default -> null;
                };
            } else if (heading != null && block instanceof Paragraph paragraph) {
                collect(heading, paragraph, questions);
            } else if (heading != null && block instanceof ListBlock list) {
                for (Node item : list.getChildren())
                    if (item instanceof ListItem && item.getFirstChild() instanceof Paragraph paragraph)
                        collect(heading, paragraph, questions);
            }
        }
        return List.copyOf(questions);
    }

    private static void collect(String heading, Paragraph paragraph, List<RequirementDecisionSection> questions) {
        var chars = paragraph.getChars().trim();
        var text = chars.toString();
        // Reformulation appends attributed decision records after the retained original.
        if (text.matches("(?s)^\\[(?:OPEN|ANSWERED|DEFERRED|NOT_APPLICABLE|CONFLICT)\\]\\s+.*")) return;
        var sentences = BreakIterator.getSentenceInstance(heading.toLowerCase(Locale.ROOT).startsWith("offene")
                ? Locale.GERMAN : Locale.ENGLISH);
        sentences.setText(text);
        var span = new SourceSpan(chars.getStartOffset(), chars.getEndOffset(), text);
        for (int start = sentences.first(), end = sentences.next(); end != BreakIterator.DONE; start = end, end = sentences.next()) {
            var sentence = chars.subSequence(start, end).trim();
            if (sentence.isEmpty() || sentence.charAt(sentence.length() - 1) != '?') continue;
            int punctuation = sentence.getEndOffset() - 1;
            for (Node inline : paragraph.getChildren()) {
                if (inline instanceof Text && punctuation >= inline.getStartOffset() && punctuation < inline.getEndOffset()) {
                    questions.add(new RequirementDecisionSection(heading,
                            new SourceSpan(sentence.getStartOffset(), sentence.getEndOffset(), sentence.toString()), span));
                    break;
                }
            }
        }
    }
}
