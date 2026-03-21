package com.taxonomy.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaxonomyRelationDtoTest {

    @Test
    void defaultConstructorSetsDefaults() {
        var dto = new TaxonomyRelationDto();

        assertNull(dto.getId());
        assertNull(dto.getSourceCode());
        assertNull(dto.getTargetCode());
        assertNull(dto.getRelationType());
        assertNull(dto.getDescription());
        assertNull(dto.getProvenance());
        assertNull(dto.getWeight());
        assertFalse(dto.isBidirectional());
    }

    @Test
    void gettersAndSettersWorkCorrectly() {
        var dto = new TaxonomyRelationDto();
        dto.setId(42L);
        dto.setSourceCode("CP-1023");
        dto.setSourceName("Capability A");
        dto.setTargetCode("CR-1047");
        dto.setTargetName("Core Service B");
        dto.setRelationType("REALIZES");
        dto.setDescription("Test description");
        dto.setProvenance("AI_GENERATED");
        dto.setWeight(85);
        dto.setBidirectional(true);

        assertEquals(42L, dto.getId());
        assertEquals("CP-1023", dto.getSourceCode());
        assertEquals("Capability A", dto.getSourceName());
        assertEquals("CR-1047", dto.getTargetCode());
        assertEquals("Core Service B", dto.getTargetName());
        assertEquals("REALIZES", dto.getRelationType());
        assertEquals("Test description", dto.getDescription());
        assertEquals("AI_GENERATED", dto.getProvenance());
        assertEquals(85, dto.getWeight());
        assertTrue(dto.isBidirectional());
    }

    @Test
    void bidirectionalDefaultIsFalse() {
        var dto = new TaxonomyRelationDto();
        assertFalse(dto.isBidirectional());
    }

    @Test
    void weightCanBeNull() {
        var dto = new TaxonomyRelationDto();
        assertNull(dto.getWeight());
        dto.setWeight(50);
        assertEquals(50, dto.getWeight());
        dto.setWeight(null);
        assertNull(dto.getWeight());
    }
}
