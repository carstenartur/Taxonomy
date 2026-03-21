package com.taxonomy.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RelationHypothesisDtoTest {

    @Test
    void defaultConstructorSetsDefaults() {
        var dto = new RelationHypothesisDto();

        assertNull(dto.getSourceCode());
        assertNull(dto.getSourceName());
        assertNull(dto.getTargetCode());
        assertNull(dto.getTargetName());
        assertNull(dto.getRelationType());
        assertEquals(0.0, dto.getConfidence());
        assertNull(dto.getReasoning());
    }

    @Test
    void fullConstructorSetsAllFields() {
        var dto = new RelationHypothesisDto(
                "CP-1023", "Capability A",
                "CR-1047", "Core Service B",
                "REALIZES", 0.85,
                "High relevance due to direct mapping");

        assertEquals("CP-1023", dto.getSourceCode());
        assertEquals("Capability A", dto.getSourceName());
        assertEquals("CR-1047", dto.getTargetCode());
        assertEquals("Core Service B", dto.getTargetName());
        assertEquals("REALIZES", dto.getRelationType());
        assertEquals(0.85, dto.getConfidence(), 0.001);
        assertEquals("High relevance due to direct mapping", dto.getReasoning());
    }

    @Test
    void confidenceCanBeZero() {
        var dto = new RelationHypothesisDto();
        dto.setConfidence(0.0);
        assertEquals(0.0, dto.getConfidence());
    }

    @Test
    void confidenceCanBeOne() {
        var dto = new RelationHypothesisDto();
        dto.setConfidence(1.0);
        assertEquals(1.0, dto.getConfidence());
    }

    @Test
    void settersOverrideConstructorValues() {
        var dto = new RelationHypothesisDto(
                "CP-1023", "Cap A", "CR-1047", "Core B",
                "REALIZES", 0.5, "initial");

        dto.setSourceCode("BP-2001");
        dto.setConfidence(0.95);
        dto.setReasoning("updated reasoning");

        assertEquals("BP-2001", dto.getSourceCode());
        assertEquals(0.95, dto.getConfidence(), 0.001);
        assertEquals("updated reasoning", dto.getReasoning());
    }
}
