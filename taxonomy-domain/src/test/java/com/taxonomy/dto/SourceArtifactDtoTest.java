package com.taxonomy.dto;

import com.taxonomy.model.SourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SourceArtifactDtoTest {

    @Test
    void defaultConstructorSetsNullValues() {
        var dto = new SourceArtifactDto();

        assertNull(dto.getId());
        assertNull(dto.getSourceType());
        assertNull(dto.getTitle());
        assertNull(dto.getCanonicalIdentifier());
        assertNull(dto.getCanonicalUrl());
        assertNull(dto.getOriginSystem());
        assertNull(dto.getAuthor());
        assertNull(dto.getDescription());
        assertNull(dto.getLanguage());
        assertNull(dto.getCreatedAt());
        assertNull(dto.getUpdatedAt());
    }

    @Test
    void twoArgConstructorSetsSourceTypeAndTitle() {
        var dto = new SourceArtifactDto(SourceType.REGULATION, "Test Regulation");

        assertEquals(SourceType.REGULATION, dto.getSourceType());
        assertEquals("Test Regulation", dto.getTitle());
        assertNull(dto.getId());
    }

    @Test
    void allGettersAndSettersWork() {
        var dto = new SourceArtifactDto();
        var now = Instant.now();

        dto.setId(1L);
        dto.setSourceType(SourceType.UPLOADED_DOCUMENT);
        dto.setTitle("My Document");
        dto.setCanonicalIdentifier("doc-001");
        dto.setCanonicalUrl("https://example.com/doc");
        dto.setOriginSystem("SAP");
        dto.setAuthor("John Doe");
        dto.setDescription("A test document");
        dto.setLanguage("en");
        dto.setCreatedAt(now);
        dto.setUpdatedAt(now);

        assertEquals(1L, dto.getId());
        assertEquals(SourceType.UPLOADED_DOCUMENT, dto.getSourceType());
        assertEquals("My Document", dto.getTitle());
        assertEquals("doc-001", dto.getCanonicalIdentifier());
        assertEquals("https://example.com/doc", dto.getCanonicalUrl());
        assertEquals("SAP", dto.getOriginSystem());
        assertEquals("John Doe", dto.getAuthor());
        assertEquals("A test document", dto.getDescription());
        assertEquals("en", dto.getLanguage());
        assertEquals(now, dto.getCreatedAt());
        assertEquals(now, dto.getUpdatedAt());
    }

    @Test
    void timestampsHandleInstantValues() {
        var dto = new SourceArtifactDto();
        var created = Instant.parse("2024-01-01T00:00:00Z");
        var updated = Instant.parse("2024-06-15T12:30:00Z");

        dto.setCreatedAt(created);
        dto.setUpdatedAt(updated);

        assertEquals(created, dto.getCreatedAt());
        assertEquals(updated, dto.getUpdatedAt());
        assertTrue(dto.getUpdatedAt().isAfter(dto.getCreatedAt()));
    }
}
