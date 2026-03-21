package com.taxonomy.provenance.service;

import com.taxonomy.dto.DocumentSection;
import com.taxonomy.dto.HierarchicalChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Produces leaf-level {@link HierarchicalChunk}s from a {@link DocumentSection}
 * tree, preserving parent context for downstream LLM analysis and RAG retrieval.
 *
 * <p>Each chunk retains its section path, heading level, parent heading text,
 * and a short "parent context" snippet (the surrounding paragraph texts from
 * the parent section) so that the LLM can understand the chunk in context.
 *
 * <p>Also provides auto-merging: when ≥2 leaf chunks from the same parent
 * section are retrieved, the service can reassemble the full parent text for
 * richer LLM prompts.
 */
@Service
public class HierarchicalChunkingService {

    private static final int MAX_PARENT_CONTEXT_LENGTH = 500;

    /**
     * Walk the document tree and produce leaf-level chunks.
     *
     * @param root the root section (from {@link StructuredDocumentParser})
     * @return list of leaf chunks with hierarchical metadata
     */
    public List<HierarchicalChunk> chunk(DocumentSection root) {
        List<HierarchicalChunk> chunks = new ArrayList<>();
        walkTree(root, chunks);
        return chunks;
    }

    /**
     * Auto-merges hit chunks that share the same parent section.
     *
     * <p>When ≥2 leaf chunks originate from the same parent heading, they are
     * merged into a single chunk whose text is the concatenation of all
     * matched chunks (preserving order). Single-occurrence chunks are returned
     * as-is.
     *
     * @param hits retrieved chunks (e.g. from a search or scoring pipeline)
     * @return merged chunks — fewer entries, each with richer context
     */
    public List<HierarchicalChunk> autoMerge(List<HierarchicalChunk> hits) {
        // Use LinkedHashMap to preserve first-encounter order of groups
        Map<Object, List<HierarchicalChunk>> byParent = hits.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getSectionPath() != null ? (Object) c.getSectionPath() : c,
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<HierarchicalChunk> result = new ArrayList<>();
        for (var entry : byParent.entrySet()) {
            List<HierarchicalChunk> group = entry.getValue();
            if (group.size() >= 2) {
                result.add(mergeGroup(group));
            } else {
                result.addAll(group);
            }
        }
        return result;
    }

    private HierarchicalChunk mergeGroup(List<HierarchicalChunk> group) {
        HierarchicalChunk first = group.get(0);
        HierarchicalChunk merged = new HierarchicalChunk();
        merged.setSectionPath(first.getSectionPath());
        merged.setLevel(first.getLevel());
        merged.setParentHeading(first.getParentHeading());
        merged.setParentContext(first.getParentContext());
        merged.setParentId(first.getParentId());

        String mergedText = group.stream()
                .map(HierarchicalChunk::getText)
                .collect(Collectors.joining("\n\n"));
        merged.setText(mergedText);
        return merged;
    }

    private void walkTree(DocumentSection section, List<HierarchicalChunk> chunks) {
        for (String para : section.getParagraphs()) {
            HierarchicalChunk chunk = new HierarchicalChunk();
            chunk.setText(para);
            chunk.setSectionPath(section.getSectionPath());
            chunk.setLevel(section.getLevel());
            chunk.setParentHeading(section.getHeading());
            chunk.setParentContext(buildParentContext(section));
            chunks.add(chunk);
        }
        for (DocumentSection child : section.getChildren()) {
            walkTree(child, chunks);
        }
    }

    /**
     * Builds a short context string from the parent section's paragraphs.
     * Truncates to {@value #MAX_PARENT_CONTEXT_LENGTH} characters.
     */
    private static String buildParentContext(DocumentSection section) {
        if (section.getParagraphs().isEmpty()) {
            return section.getHeading();
        }
        StringBuilder sb = new StringBuilder();
        for (String p : section.getParagraphs()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(p);
            if (sb.length() >= MAX_PARENT_CONTEXT_LENGTH) break;
        }
        String ctx = sb.toString();
        return ctx.length() > MAX_PARENT_CONTEXT_LENGTH
                ? ctx.substring(0, MAX_PARENT_CONTEXT_LENGTH) + "…"
                : ctx;
    }
}
