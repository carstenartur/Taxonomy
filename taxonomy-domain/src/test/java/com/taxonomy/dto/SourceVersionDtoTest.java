package com.taxonomy.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SourceVersionDtoTest {

    @Test
    void defaultConstructorSetsNullValues() {
        var dto = new SourceVersionDto();

        assertNull(dto.getId());
        assertNull(dto.getSourceArtifactId());
        assertNull(dto.getVersionLabel());
        assertNull(dto.getRetrievedAt());
        assertNull(dto.getEffectiveDate());
        assertNull(dto.getContentHash());
        assertNull(dto.getMimeType());
        assertNull(dto.getStorageLocation());
        assertNull(dto.getRawTextLocation());
    }

    @Test
    void allGettersAndSettersWork() {
        var dto = new SourceVersionDto();
        var now = Instant.now();
        var date = LocalDate.of(2024, 3, 15);

        dto.setId(1L);
        dto.setSourceArtifactId(42L);
        dto.setVersionLabel("v2.0");
        dto.setRetrievedAt(now);
        dto.setEffectiveDate(date);
        dto.setContentHash("sha256-abc123");
        dto.setMimeType("application/pdf");
        dto.setStorageLocation("/data/docs/v2.pdf");
        dto.setRawTextLocation("/data/text/v2.txt");

        assertEquals(1L, dto.getId());
        assertEquals(42L, dto.getSourceArtifactId());
        assertEquals("v2.0", dto.getVersionLabel());
        assertEquals(now, dto.getRetrievedAt());
        assertEquals(date, dto.getEffectiveDate());
        assertEquals("sha256-abc123", dto.getContentHash());
        assertEquals("application/pdf", dto.getMimeType());
        assertEquals("/data/docs/v2.pdf", dto.getStorageLocation());
        assertEquals("/data/text/v2.txt", dto.getRawTextLocation());
    }

    @Test
    void instantAndLocalDateCoexist() {
        var dto = new SourceVersionDto();
        dto.setRetrievedAt(Instant.parse("2024-06-01T10:00:00Z"));
        dto.setEffectiveDate(LocalDate.of(2024, 6, 1));

        assertNotNull(dto.getRetrievedAt());
        assertNotNull(dto.getEffectiveDate());
    }
}
