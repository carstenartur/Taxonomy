package com.taxonomy.provenance.service;

import com.taxonomy.dto.DocumentSection;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Builds a hierarchical {@link DocumentSection} tree from raw text.
 *
 * <p>Re-uses the heading detection logic of {@link DocumentParserService} to
 * identify section boundaries, then organises the paragraphs under a
 * parent–child tree structure. The tree is suitable for hierarchical chunking
 * and context-aware LLM prompts.
 */
@Service
public class StructuredDocumentParser {

    private static final int MIN_PARAGRAPH_LENGTH = 40;

    private final DocumentParserService parserService;

    public StructuredDocumentParser(DocumentParserService parserService) {
        this.parserService = parserService;
    }

    /**
     * Parses raw text into a hierarchical document tree.
     *
     * @param rawText the raw text (with double-newline paragraph separators)
     * @return the root {@link DocumentSection} (level 0) containing the full tree
     */
    public DocumentSection parse(String rawText) {
        DocumentSection root = new DocumentSection(0, "Document Root");
        root.setSectionPath("Document Root");

        if (rawText == null || rawText.isBlank()) {
            return root;
        }

        Deque<DocumentSection> stack = new ArrayDeque<>();
        stack.push(root);

        String[] paragraphs = rawText.split("\\n\\s*\\n");

        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;

            DocumentParserService.HeadingMatch heading = parserService.detectHeading(trimmed);
            if (heading != null) {
                DocumentSection section = new DocumentSection(heading.level(), heading.text());

                // Pop until we find a parent with a strictly smaller level
                while (stack.size() > 1 && stack.peek().getLevel() >= heading.level()) {
                    stack.pop();
                }

                DocumentSection parent = stack.peek();
                section.setSectionPath(buildSectionPath(parent, heading.text()));
                parent.getChildren().add(section);
                stack.push(section);
            } else if (trimmed.length() >= MIN_PARAGRAPH_LENGTH) {
                stack.peek().getParagraphs().add(trimmed);
            }
        }

        return root;
    }

    private static String buildSectionPath(DocumentSection parent, String heading) {
        if (parent.getLevel() == 0) {
            return heading;
        }
        return parent.getSectionPath() + " > " + heading;
    }
}
