package com.taxonomy.provenance;

import com.taxonomy.dto.DocumentSection;
import com.taxonomy.dto.HierarchicalChunk;
import com.taxonomy.provenance.service.HierarchicalChunkingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HierarchicalChunkingService}.
 */
class HierarchicalChunkingServiceTest {

    private HierarchicalChunkingService chunkingService;

    @BeforeEach
    void setUp() {
        chunkingService = new HierarchicalChunkingService();
    }

    @Test
    void emptyTreeProducesNoChunks() {
        DocumentSection root = new DocumentSection(0, "Root");
        List<HierarchicalChunk> chunks = chunkingService.chunk(root);
        assertThat(chunks).isEmpty();
    }

    @Test
    void rootParagraphsProduceChunks() {
        DocumentSection root = new DocumentSection(0, "Root");
        root.setSectionPath("Root");
        root.getParagraphs().add("First paragraph with enough content.");
        root.getParagraphs().add("Second paragraph describing more requirements.");

        List<HierarchicalChunk> chunks = chunkingService.chunk(root);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getText()).isEqualTo("First paragraph with enough content.");
        assertThat(chunks.get(0).getLevel()).isZero();
        assertThat(chunks.get(0).getParentHeading()).isEqualTo("Root");
    }

    @Test
    void nestedSectionsProduce_chunksWithHierarchicalContext() {
        DocumentSection root = new DocumentSection(0, "Document Root");
        root.setSectionPath("Document Root");

        DocumentSection ch1 = new DocumentSection(1, "Chapter 1");
        ch1.setSectionPath("Chapter 1");
        root.getChildren().add(ch1);

        DocumentSection sec1 = new DocumentSection(2, "Section 1.1");
        sec1.setSectionPath("Chapter 1 > Section 1.1");
        sec1.getParagraphs().add("The system must enforce access control.");
        ch1.getChildren().add(sec1);

        DocumentSection sec2 = new DocumentSection(2, "Section 1.2");
        sec2.setSectionPath("Chapter 1 > Section 1.2");
        sec2.getParagraphs().add("All data must be encrypted at rest.");
        ch1.getChildren().add(sec2);

        List<HierarchicalChunk> chunks = chunkingService.chunk(root);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getSectionPath()).isEqualTo("Chapter 1 > Section 1.1");
        assertThat(chunks.get(0).getParentHeading()).isEqualTo("Section 1.1");
        assertThat(chunks.get(1).getSectionPath()).isEqualTo("Chapter 1 > Section 1.2");
    }

    @Test
    void parentContextIsTruncated() {
        DocumentSection root = new DocumentSection(0, "Root");
        root.setSectionPath("Root");
        // Add a very long paragraph to test truncation
        root.getParagraphs().add("A".repeat(600));

        List<HierarchicalChunk> chunks = chunkingService.chunk(root);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getParentContext().length()).isLessThanOrEqualTo(501);
        assertThat(chunks.get(0).getParentContext()).endsWith("…");
    }

    @Test
    void autoMergeCombinesChunksFromSameSection() {
        HierarchicalChunk c1 = new HierarchicalChunk();
        c1.setText("First paragraph.");
        c1.setSectionPath("Chapter 1 > Section 1.1");
        c1.setLevel(2);
        c1.setParentHeading("Section 1.1");

        HierarchicalChunk c2 = new HierarchicalChunk();
        c2.setText("Second paragraph.");
        c2.setSectionPath("Chapter 1 > Section 1.1");
        c2.setLevel(2);
        c2.setParentHeading("Section 1.1");

        HierarchicalChunk c3 = new HierarchicalChunk();
        c3.setText("Different section paragraph.");
        c3.setSectionPath("Chapter 2");
        c3.setLevel(1);
        c3.setParentHeading("Chapter 2");

        List<HierarchicalChunk> merged = chunkingService.autoMerge(List.of(c1, c2, c3));

        // c1 + c2 should be merged, c3 stays separate
        assertThat(merged).hasSize(2);
        boolean foundMerged = merged.stream()
                .anyMatch(c -> c.getText().contains("First paragraph.")
                        && c.getText().contains("Second paragraph."));
        assertThat(foundMerged).isTrue();
    }

    @Test
    void autoMergeSingleChunksPassThrough() {
        HierarchicalChunk c1 = new HierarchicalChunk();
        c1.setText("Only chunk.");
        c1.setSectionPath("Chapter 1");
        c1.setLevel(1);

        List<HierarchicalChunk> merged = chunkingService.autoMerge(List.of(c1));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).getText()).isEqualTo("Only chunk.");
    }
}
