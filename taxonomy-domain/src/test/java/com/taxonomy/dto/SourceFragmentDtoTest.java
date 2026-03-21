package com.taxonomy.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SourceFragmentDtoTest {

    @Test
    void defaultConstructorSetsNullValues() {
        var dto = new SourceFragmentDto();

        assertNull(dto.getId());
        assertNull(dto.getSourceVersionId());
        assertNull(dto.getSectionPath());
        assertNull(dto.getParagraphRef());
        assertNull(dto.getPageFrom());
        assertNull(dto.getPageTo());
        assertNull(dto.getFragmentText());
        assertNull(dto.getFragmentHash());
    }

    @Test
    void allGettersAndSettersWork() {
        var dto = new SourceFragmentDto();
        dto.setId(1L);
        dto.setSourceVersionId(42L);
        dto.setSectionPath("chapter-3/section-2");
        dto.setParagraphRef("§4.1");
        dto.setPageFrom(10);
        dto.setPageTo(12);
        dto.setFragmentText("This is the fragment text content.");
        dto.setFragmentHash("sha256-fragment-hash");

        assertEquals(1L, dto.getId());
        assertEquals(42L, dto.getSourceVersionId());
        assertEquals("chapter-3/section-2", dto.getSectionPath());
        assertEquals("§4.1", dto.getParagraphRef());
        assertEquals(10, dto.getPageFrom());
        assertEquals(12, dto.getPageTo());
        assertEquals("This is the fragment text content.", dto.getFragmentText());
        assertEquals("sha256-fragment-hash", dto.getFragmentHash());
    }

    @Test
    void pageRangeCanBeSinglePage() {
        var dto = new SourceFragmentDto();
        dto.setPageFrom(5);
        dto.setPageTo(5);

        assertEquals(dto.getPageFrom(), dto.getPageTo());
    }

    @Test
    void pageRangeFieldsAreIndependent() {
        var dto = new SourceFragmentDto();
        dto.setPageFrom(3);

        assertEquals(3, dto.getPageFrom());
        assertNull(dto.getPageTo());
    }
}
